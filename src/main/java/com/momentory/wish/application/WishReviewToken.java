package com.momentory.wish.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 심사를 지난 글이라는 표 — 심사와 띄우기 사이에 글을 저장하지 않으려고 서명만 건넨다.
 *
 * <p>글을 조금이라도 바꾸면 서명이 맞지 않아 심사를 건너뛰고 띄울 수 없다.
 */
@Component
public class WishReviewToken {

    static final Duration VALID_FOR = Duration.ofMinutes(30);

    private final byte[] key;

    public WishReviewToken(@Value("${jwt.secret}") String secret) {
        this.key = ("wish-review:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String issue(Long userId, String content, boolean light, Instant now) {
        String head = now.plus(VALID_FOR).getEpochSecond() + "." + (light ? 1 : 0);
        return head + "." + sign(userId, content, head);
    }

    /** 맞는 표면 쪽지에 돌려도 되는 글인지를 돌려주고, 틀리거나 지난 표면 비어 있다. */
    public Optional<Boolean> verify(Long userId, String content, String token, Instant now) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String head = parts[0] + "." + parts[1];
        boolean same = MessageDigest.isEqual(
                sign(userId, content, head).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
        if (!same) {
            return Optional.empty();
        }
        try {
            return Long.parseLong(parts[0]) < now.getEpochSecond()
                    ? Optional.empty()
                    : Optional.of("1".equals(parts[1]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String sign(Long userId, String content, String head) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signed = mac.doFinal((userId + "\n" + head + "\n" + content).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
