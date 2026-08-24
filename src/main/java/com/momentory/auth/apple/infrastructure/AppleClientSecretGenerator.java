package com.momentory.auth.apple.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 애플 서버 API(token · revoke)를 부를 때 쓰는 {@code client_secret} 을 만든다.
 *
 * <p>애플은 고정된 비밀 문자열을 받지 않는다. <b>우리가 개발자 키로 서명한 짧은
 * JWT</b> 를 client_secret 자리에 넣는다:
 *
 * <pre>
 * header  { alg: ES256, kid: &lt;Key ID&gt; }
 * payload { iss: &lt;Team ID&gt;, aud: https://appleid.apple.com,
 *           sub: &lt;번들 ID&gt;, iat, exp }
 * </pre>
 *
 * <p>서명 키가 {@code .p8} 이다 — <b>이 파일만이 비밀이고</b> Team ID·Key ID·번들 ID 는
 * 전부 공개 식별자다.
 *
 * <p><b>수명을 5분으로 짧게 잡는다.</b> 애플은 6개월까지 허용하지만 길게 만들 이유가
 * 없다 — 매 호출마다 새로 서명하는 비용은 무시할 만하고, 새어 나간 값이 오래 살아
 * 있지 않는 편이 낫다.
 *
 * <p><b>만든 값을 로그에 남기지 않는다</b>(`docs/CONTRIBUTING.md` 「값을 다루는 세 등급」).
 */
@Component
public class AppleClientSecretGenerator {

    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    private final AppleAuthProperties properties;

    public AppleClientSecretGenerator(AppleAuthProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws AppleApiException 설정이 없거나 {@code .p8} 을 읽을 수 없을 때.
     *         <b>부르기 전에 {@link AppleAuthProperties#revokeConfigured()} 로 거른다</b> —
     *         여기까지 왔는데 설정이 없으면 그것은 호출부의 실수다.
     */
    public String generate() {
        if (!properties.revokeConfigured()) {
            throw new AppleApiException(
                    AppleApiErrorCode.REVOKE_NOT_CONFIGURED,
                    "Apple revoke credentials are not configured."
            );
        }

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.teamId())
                .audience(APPLE_AUDIENCE)
                // 애플이 보는 것은 우리 앱의 번들 ID 다 — 로그인 검증의 aud 와 같은 값
                .subject(properties.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.keyId()).build(),
                    claims
            );
            jwt.sign(new ECDSASigner(readPrivateKey(properties.privateKey())));
            return jwt.serialize();
        } catch (AppleApiException exception) {
            throw exception;
        } catch (Exception exception) {
            // 예외 메시지에 키 내용이 실리지 않게 원인만 넘긴다
            throw new AppleApiException(
                    AppleApiErrorCode.REVOKE_NOT_CONFIGURED,
                    "Apple client secret could not be signed.",
                    exception
            );
        }
    }

    /**
     * {@code .p8} 내용(PKCS#8 PEM)을 EC 개인키로 읽는다.
     *
     * <p>환경변수로 들어오는 값이라 <b>줄바꿈이 어떤 모양이든 받아 준다</b> — 진짜 개행일
     * 수도 있고 {@code \n} 두 글자로 들어올 수도 있다. 여기서 막히면 「설정은 했는데 탈퇴가
     * 안 된다」가 되고, 원인이 눈에 보이지 않는다.
     */
    private static ECPrivateKey readPrivateKey(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64Body(pem));
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * PEM 에서 base64 본문만 남긴다.
     *
     * <p>정규식이나 문자열 치환 대신 <b>글자를 하나씩 고른다.</b> 이 값이 어떤 모양으로
     * 올지 알 수 없기 때문이다 — 진짜 개행(LF)일 수도, CRLF 일 수도, 환경변수를 거치며
     * 백슬래시+n 두 글자가 됐을 수도 있다. 셋 다 여기서 함께 걸러진다.
     *
     * <p>백슬래시(아스키 92)를 만나면 <b>그 다음 글자까지 버린다</b> — 그러지 않으면
     * {@code 
} 의 {@code n} 이 base64 글자라 그대로 남아 본문을 망가뜨린다.
     */
    private static String base64Body(String pem) {
        String withoutMarkers = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "");

        StringBuilder body = new StringBuilder(withoutMarkers.length());
        boolean afterBackslash = false;
        for (int i = 0; i < withoutMarkers.length(); i++) {
            char character = withoutMarkers.charAt(i);
            if (character == 92) {
                afterBackslash = true;
                continue;
            }
            if (afterBackslash) {
                afterBackslash = false;
                continue;
            }
            if (isBase64Character(character)) {
                body.append(character);
            }
        }
        return body.toString();
    }

    private static boolean isBase64Character(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '+'
                || character == '/'
                || character == '=';
    }
}
