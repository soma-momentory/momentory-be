package com.momentory.auth.google.infrastructure;

import com.momentory.auth.google.application.GoogleUserInfo;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdTokenVerifierTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String ISSUER_WITHOUT_SCHEME = "accounts.google.com";

    private static final String WEB_CLIENT_ID = "web.apps.googleusercontent.com";
    private static final String IOS_CLIENT_ID = "ios.apps.googleusercontent.com";
    private static final String ANDROID_CLIENT_ID = "android.apps.googleusercontent.com";

    private static final String PROVIDER_USER_ID = "109876543210987654321";

    private MockWebServer server;
    private RSAKey googleKey;
    private MockResponse jwkSetResponse;

    @BeforeEach
    void startServer() throws Exception {
        googleKey = generateKey("google-key");
        server = new MockWebServer();
        jwkSetResponse = jsonResponse(new JWKSet(googleKey.toPublicJWK()).toString());
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
    void returnsGoogleUserInfoForValidIdToken() {
        String idToken = signedToken(googleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        GoogleUserInfo userInfo = verifier().verify(idToken);

        assertThat(userInfo.providerUserId()).isEqualTo(PROVIDER_USER_ID);
        assertThat(userInfo.email()).isEqualTo("user@example.com");
    }

    @Test
    void acceptsIssuerWithoutScheme() {
        // 구글은 같은 토큰을 두 형태의 iss 로 발급한다 — 둘 다 받아야 한다
        String idToken = signedToken(googleKey, claims()
                .issuer(ISSUER_WITHOUT_SCHEME)
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertThat(verifier().verify(idToken).email()).isEqualTo("user@example.com");
    }

    @Test
    void acceptsIosClientIdAsAudience() {
        // iOS 는 aud 에 iOS 클라이언트 ID 가 앉는다 — 애플처럼 값 하나로 잠글 수 없다
        String idToken = signedToken(googleKey, claims()
                .audience(IOS_CLIENT_ID)
                .claim("azp", IOS_CLIENT_ID)
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertThat(verifier().verify(idToken).email()).isEqualTo("user@example.com");
    }

    @Test
    void acceptsEmailVerifiedSentAsString() {
        String idToken = signedToken(googleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", "true"));

        assertThat(verifier().verify(idToken).email()).isEqualTo("user@example.com");
    }

    @Test
    void acceptsIdTokenWithoutAuthorizedPartyClaim() {
        // azp 는 항상 오지는 않는다 — 없다고 거절하면 정상 로그인이 막힌다
        JWTClaimsSet.Builder withoutAzp = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(PROVIDER_USER_ID)
                .audience(WEB_CLIENT_ID)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("email", "user@example.com")
                .claim("email_verified", true);

        assertThat(verifier().verify(signedToken(googleKey, withoutAzp)).email())
                .isEqualTo("user@example.com");
    }

    @Test
    void rejectsBlankIdToken() {
        assertError(GoogleApiErrorCode.INVALID_ID_TOKEN, () -> verifier().verify("   "));
    }

    @Test
    void rejectsMalformedIdToken() {
        assertError(GoogleApiErrorCode.INVALID_ID_TOKEN, () -> verifier().verify("not-a-jwt"));
    }

    @Test
    void rejectsIdTokenSignedByUnknownKey() throws Exception {
        String idToken = signedToken(generateKey("attacker-key"), claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.INVALID_ID_TOKEN, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsExpiredIdToken() {
        String idToken = signedToken(googleKey, claims()
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.INVALID_ID_TOKEN, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsIdTokenFromAnotherIssuer() {
        String idToken = signedToken(googleKey, claims()
                .issuer("https://attacker.example.com")
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.INVALID_ID_TOKEN, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsIdTokenIssuedForAnotherProject() {
        String idToken = signedToken(googleKey, claims()
                .audience("someone-else.apps.googleusercontent.com")
                .claim("azp", "someone-else.apps.googleusercontent.com")
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.CLIENT_ID_MISMATCH, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsIdTokenRequestedByAnotherApp() {
        // 우리 웹 클라이언트 ID 를 aud 로 지정해 남의 앱이 받아 간 토큰 —
        // aud 만 보면 통과한다. azp 가 그것을 잡는 자리다
        String idToken = signedToken(googleKey, claims()
                .claim("azp", "another-app.apps.googleusercontent.com")
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.CLIENT_ID_MISMATCH, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsIdTokenWithoutEmail() {
        String idToken = signedToken(googleKey, claims().claim("email_verified", true));

        assertError(GoogleApiErrorCode.EMAIL_UNAVAILABLE, () -> verifier().verify(idToken));
    }

    @Test
    void rejectsUnverifiedEmail() {
        String idToken = signedToken(googleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", false));

        assertError(GoogleApiErrorCode.EMAIL_UNAVAILABLE, () -> verifier().verify(idToken));
    }

    @Test
    void mapsPublicKeyServerErrorToServerError() {
        jwkSetResponse = new MockResponse().setResponseCode(500);
        String idToken = signedToken(googleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.GOOGLE_API_SERVER_ERROR, () -> verifier().verify(idToken));
    }

    @Test
    void mapsConnectionFailureAsNetworkError() {
        String idToken = signedToken(googleKey, claims()
                .claim("email", "user@example.com")
                .claim("email_verified", true));

        assertError(GoogleApiErrorCode.GOOGLE_API_NETWORK_ERROR, () -> unavailableVerifier().verify(idToken));
    }

    private GoogleIdTokenVerifier verifier() {
        return new GoogleIdTokenVerifier(new GoogleAuthProperties(
                server.url("/oauth2/v3/certs").toString(),
                List.of(ISSUER, ISSUER_WITHOUT_SCHEME),
                List.of(WEB_CLIENT_ID, IOS_CLIENT_ID, ANDROID_CLIENT_ID),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        ));
    }

    private GoogleIdTokenVerifier unavailableVerifier() {
        return new GoogleIdTokenVerifier(new GoogleAuthProperties(
                "http://127.0.0.1:1/oauth2/v3/certs",
                List.of(ISSUER, ISSUER_WITHOUT_SCHEME),
                List.of(WEB_CLIENT_ID, IOS_CLIENT_ID, ANDROID_CLIENT_ID),
                Duration.ofMillis(100),
                Duration.ofMillis(100)
        ));
    }

    /** 기본은 Android 모양이다 — aud 는 웹 클라이언트, azp 는 Android 클라이언트 */
    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(PROVIDER_USER_ID)
                .audience(WEB_CLIENT_ID)
                .claim("azp", ANDROID_CLIENT_ID)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)));
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

    private RSAKey generateKey(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyID(keyId).generate();
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private void assertError(GoogleApiErrorCode expectedCode, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOfSatisfying(GoogleApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .withFailMessage("Expected %s but cause was %s", expectedCode, exception.getCause())
                                .isEqualTo(expectedCode)
                );
    }
}
