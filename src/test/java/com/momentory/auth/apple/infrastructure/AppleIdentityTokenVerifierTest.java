package com.momentory.auth.apple.infrastructure;

import com.momentory.auth.apple.application.AppleUserInfo;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleIdentityTokenVerifierTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String CLIENT_ID = "com.momentory.app";
    private static final String PROVIDER_USER_ID = "001234.abcdef.0000";

    /**
     * 앱이 이번 인가에 쓴 nonce 의 <b>원문</b>. 애플에게는 이 값의 SHA-256 hex 가
     * 가고, 그것이 토큰의 {@code nonce} 클레임으로 돌아온다(FE {@code appleSdk.ts}).
     */
    private static final String RAW_NONCE = "3f1a9c00deadbeef";

    private MockWebServer server;
    private RSAKey appleKey;
    private MockResponse jwkSetResponse;

    @BeforeEach
    void startServer() throws Exception {
        appleKey = generateKey("apple-key");
        server = new MockWebServer();
        jwkSetResponse = jsonResponse(new JWKSet(appleKey.toPublicJWK()).toString());
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return jwkSetResponse;
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsAppleUserInfoForValidIdentityToken() {
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        AppleUserInfo userInfo = verifier().verify(identityToken, RAW_NONCE);

        assertThat(userInfo.providerUserId()).isEqualTo(PROVIDER_USER_ID);
        assertThat(userInfo.email()).isEqualTo("user@example.com");
    }

    @Test
    void acceptsEmailVerifiedSentAsString() {
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", "true"));

        assertThat(verifier().verify(identityToken, RAW_NONCE).email()).isEqualTo("user@example.com");
    }

    @Test
    void rejectsBlankIdentityToken() {
        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify("   ", RAW_NONCE));
    }

    @Test
    void rejectsMalformedIdentityToken() {
        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify("not-a-jwt", RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenSignedByUnknownKey() throws Exception {
        String identityToken = signedToken(generateKey("attacker-key"), claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsExpiredIdentityToken() {
        String identityToken = signedToken(appleKey, claims()
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenFromAnotherIssuer() {
        String identityToken = signedToken(appleKey, claims()
                .issuer("https://attacker.example.com")
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenIssuedForAnotherClient() {
        String identityToken = signedToken(appleKey, claims()
                .audience("com.other.app")
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.CLIENT_ID_MISMATCH, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenWithoutEmail() {
        String identityToken = signedToken(appleKey, claims().claim("email_verified", true));

        assertError(AppleApiErrorCode.EMAIL_UNAVAILABLE, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsUnverifiedEmail() {
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", false));

        assertError(AppleApiErrorCode.EMAIL_UNAVAILABLE, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenWhoseNonceDoesNotMatchTheRequest() {
        // 다른 인가에서 주운 토큰을 끼워 넣은 모양 — 원문을 모르면 통과할 수 없다
        String identityToken = signedToken(appleKey, claims()
                .claim("nonce", sha256Hex("someone-elses-nonce"))
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.NONCE_MISMATCH, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void rejectsIdentityTokenWithoutNonceClaim() {
        // 검사할 수 없으면 통과시키지 않는다 — 그건 검사를 없앤 것과 같다
        JWTClaimsSet.Builder withoutNonce = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(PROVIDER_USER_ID)
                .audience(CLIENT_ID)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("email", "user@example.com")
                .claim("email_verified", true);

        assertError(AppleApiErrorCode.NONCE_MISMATCH,
                () -> verifier().verify(signedToken(appleKey, withoutNonce), RAW_NONCE));
    }

    @Test
    void rejectsBlankNonce() {
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.INVALID_IDENTITY_TOKEN, () -> verifier().verify(identityToken, "  "));
    }

    @Test
    void rejectsRawNonceSentWhereHashWasExpected() {
        // FE 가 실수로 해시 대신 원문을 애플에 준 경우 — 규약이 뒤바뀌면 전부 막힌다
        String identityToken = signedToken(appleKey, claims()
                .claim("nonce", RAW_NONCE)
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.NONCE_MISMATCH, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void mapsPublicKeyServerErrorToServerError() {
        jwkSetResponse = new MockResponse().setResponseCode(500);
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.APPLE_API_SERVER_ERROR, () -> verifier().verify(identityToken, RAW_NONCE));
    }

    @Test
    void mapsConnectionFailureAsNetworkError() {
        String identityToken = signedToken(appleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(AppleApiErrorCode.APPLE_API_NETWORK_ERROR, () -> unavailableVerifier().verify(identityToken, RAW_NONCE));
    }

    private AppleIdentityTokenVerifier verifier() {
        return new AppleIdentityTokenVerifier(new AppleAuthProperties(
                server.url("/auth/keys").toString(),
                ISSUER,
                CLIENT_ID,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        ));
    }

    private AppleIdentityTokenVerifier unavailableVerifier() {
        return new AppleIdentityTokenVerifier(new AppleAuthProperties(
                "http://127.0.0.1:1/auth/keys",
                ISSUER,
                CLIENT_ID,
                Duration.ofMillis(100),
                Duration.ofMillis(100)
        ));
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(PROVIDER_USER_ID)
                .audience(CLIENT_ID)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("nonce", sha256Hex(RAW_NONCE));
    }

    private String signedToken(RSAKey signingKey, JWTClaimsSet.Builder claims) {
        try {
            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims.build()
            );
            signedJWT.sign(new RSASSASigner(signingKey));
            return signedJWT.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * <b>일부러 프로덕션 코드를 부르지 않는다.</b> 검증 대상과 기대값을 같은 구현으로
     * 만들면 그 구현이 틀려도 테스트는 통과한다 — 여기서는 규약(소문자 hex)을
     * 독립적으로 다시 쓴다.
     */
    private static String sha256Hex(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RSAKey generateKey(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyID(keyId).generate();
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private void assertError(AppleApiErrorCode expectedCode, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOfSatisfying(AppleApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .withFailMessage("Expected %s but cause was %s", expectedCode, exception.getCause())
                                .isEqualTo(expectedCode)
                );
    }
}
