package com.momentory.auth.apple.infrastructure;

import com.momentory.auth.apple.application.AppleUserInfo;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Apple ID 서버가 발급한 identity token(JWT)을 Apple 공개키(JWKS)로 검증한다.
 * 서명·발급자·만료는 Nimbus 디코더가, 대상(aud)과 이메일 신뢰성은 이 클래스가 확인한다.
 * Apple 은 최초 승인 뒤의 로그인에서는 이메일 클레임을 생략할 수 있으므로, 이메일은
 * <b>있을 때만</b> 검증한다. 사용자를 식별하는 값은 이메일이 아니라 검증된 {@code sub}다.
 */
@Component
public class AppleIdentityTokenVerifier {

    private static final String NONCE_CLAIM = "nonce";
    private static final String EMAIL_CLAIM = "email";
    private static final String EMAIL_VERIFIED_CLAIM = "email_verified";
    private static final int MAX_EMAIL_LENGTH = 320;

    private final NimbusJwtDecoder jwtDecoder;
    private final AppleAuthProperties properties;

    public AppleIdentityTokenVerifier(AppleAuthProperties properties) {
        this.properties = properties;
        this.jwtDecoder = createDecoder(properties);
    }

    /**
     * identity token 을 검증하고 사용자 정보를 꺼낸다.
     *
     * <p>{@code rawNonce} 는 앱이 이번 인가에 쓴 nonce 의 <b>원문</b>이다. 앱은 애플에게
     * 그 값의 SHA-256 hex 를 주고(FE {@code appleSdk.ts}), 애플은 그것을 그대로 토큰의
     * {@code nonce} 클레임에 넣는다. 그래서 여기서는 원문을 해시해 클레임과 맞춰 본다 —
     * <b>원문을 아는 쪽만 그 토큰을 쓸 수 있다.</b>
     *
     * <p>인코딩은 양쪽 다 <b>소문자 hex</b> 다. 갈리면 항상 불일치라 로그인이 통째로
     * 막히므로, 이 규약이 FE 와의 계약이다.
     */
    public AppleUserInfo verify(String identityToken, String rawNonce) {
        if (identityToken == null || identityToken.isBlank()) {
            throw new AppleApiException(
                    AppleApiErrorCode.INVALID_IDENTITY_TOKEN,
                    "Apple identity token is required."
            );
        }
        if (rawNonce == null || rawNonce.isBlank()) {
            throw new AppleApiException(
                    AppleApiErrorCode.INVALID_IDENTITY_TOKEN,
                    "Apple nonce is required."
            );
        }

        Jwt jwt = decode(identityToken);

        requireMatchingNonce(jwt, rawNonce);
        if (!jwt.getAudience().contains(properties.clientId())) {
            throw new AppleApiException(
                    AppleApiErrorCode.CLIENT_ID_MISMATCH,
                    "Apple identity token belongs to a different client."
            );
        }

        String providerUserId = jwt.getSubject();
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new AppleApiException(
                    AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE,
                    "Apple user ID is missing."
            );
        }

        return new AppleUserInfo(providerUserId, verifiedEmailIfPresent(jwt));
    }

    /**
     * 토큰의 {@code nonce} 클레임이 원문의 SHA-256 hex 와 같은지 본다.
     *
     * <p><b>클레임이 없으면 거절한다.</b> 없다는 것은 앱이 nonce 를 주지 않고 받아 온
     * 토큰이라는 뜻이고, 그런 토큰은 재생 여부를 가릴 수 없다 — 「검사할 수 없으면
     * 통과」는 검사를 없앤 것과 같다.
     *
     * <p>비교는 {@link MessageDigest#isEqual}로 한다. 길이가 고정된 해시라 실익은 작지만,
     * 비밀을 맞춰 보는 자리는 타이밍에 기대지 않는 쪽으로 통일한다.
     */
    private void requireMatchingNonce(Jwt jwt, String rawNonce) {
        String tokenNonce = jwt.getClaimAsString(NONCE_CLAIM);
        if (tokenNonce == null || tokenNonce.isBlank()) {
            throw new AppleApiException(
                    AppleApiErrorCode.NONCE_MISMATCH,
                    "Apple identity token has no nonce claim."
            );
        }

        String expected = sha256Hex(rawNonce);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                tokenNonce.getBytes(StandardCharsets.UTF_8))) {
            throw new AppleApiException(
                    AppleApiErrorCode.NONCE_MISMATCH,
                    "Apple identity token nonce does not match the request."
            );
        }
    }

    private static String sha256Hex(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 은 모든 JRE 가 반드시 갖는다 — 여기 오면 런타임이 깨진 것이다
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private Jwt decode(String identityToken) {
        try {
            return jwtDecoder.decode(identityToken);
        } catch (BadJwtException exception) {
            throw new AppleApiException(
                    AppleApiErrorCode.INVALID_IDENTITY_TOKEN,
                    "Apple identity token is invalid or expired.",
                    exception
            );
        } catch (JwtException exception) {
            throw mapKeyRetrievalException(exception);
        }
    }

    private String verifiedEmailIfPresent(Jwt jwt) {
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (email == null || email.isBlank()) {
            return null;
        }
        if (!isEmailVerified(jwt) || email.length() > MAX_EMAIL_LENGTH) {
            throw new AppleApiException(
                    AppleApiErrorCode.EMAIL_UNAVAILABLE,
                    "Apple identity token contains an unusable email."
            );
        }
        return email.trim();
    }

    /** Apple 은 email_verified 를 boolean 또는 문자열("true")로 내려준다. */
    private boolean isEmailVerified(Jwt jwt) {
        Object emailVerified = jwt.getClaim(EMAIL_VERIFIED_CLAIM);
        if (emailVerified instanceof Boolean verified) {
            return verified;
        }
        return emailVerified instanceof String verified && Boolean.parseBoolean(verified);
    }

    private AppleApiException mapKeyRetrievalException(JwtException exception) {
        if (isNetworkException(exception)) {
            return new AppleApiException(
                    AppleApiErrorCode.APPLE_API_NETWORK_ERROR,
                    "Apple public keys could not be reached.",
                    exception
            );
        }
        if (isServerError(exception)) {
            return new AppleApiException(
                    AppleApiErrorCode.APPLE_API_SERVER_ERROR,
                    "Apple public key server failed.",
                    exception
            );
        }
        return new AppleApiException(
                AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE,
                "Apple public keys could not be processed.",
                exception
        );
    }

    private boolean isNetworkException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isServerError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().is5xxServerError()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static NimbusJwtDecoder createDecoder(AppleAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
