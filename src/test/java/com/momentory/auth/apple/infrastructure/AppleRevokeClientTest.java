package com.momentory.auth.apple.infrastructure;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연결 해제 시험 — <b>무엇을 보내고, 실패를 어떻게 다루는가.</b>
 *
 * <p>주제는 둘이다:
 *
 * <ol>
 *   <li><b>실패하면 던진다</b> — 카카오 unlink 와 같은 규약이다. 연결을 못 끊었는데
 *       계정만 지우면 되돌릴 수 없다</li>
 *   <li><b>설정이 없으면 지나간다</b> — 계정 삭제 자체도 심사 규정이 요구하는 기능이라,
 *       {@code .p8} 이 없다고 탈퇴를 통째로 막는 쪽이 더 나쁘다</li>
 * </ol>
 */
class AppleRevokeClientTest {

    private static final String CLIENT_ID = "kr.momentory.app";
    private static final String REFRESH_TOKEN = "apple-refresh-token-secret";

    private MockWebServer server;
    private String pem;

    @BeforeEach
    void startServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void sendsTokenAndSignedClientSecret() throws Exception {
        server.enqueue(jsonResponse("""
                {"access_token":"apple-access-token","token_type":"Bearer","expires_in":3600}
                """));
        server.enqueue(new MockResponse().setResponseCode(200));

        client(pem).revoke(REFRESH_TOKEN);

        RecordedRequest validationRequest = server.takeRequest();
        String validationBody = validationRequest.getBody().readUtf8();
        assertThat(validationRequest.getPath()).isEqualTo("/auth/token");
        assertThat(validationBody).contains("grant_type=refresh_token");
        assertThat(validationBody).contains("refresh_token=" + REFRESH_TOKEN);
        assertThat(validationBody).contains("client_id=" + CLIENT_ID);
        assertThat(validationBody).contains("client_secret=");

        RecordedRequest revokeRequest = server.takeRequest();
        String revokeBody = revokeRequest.getBody().readUtf8();
        assertThat(revokeRequest.getPath()).isEqualTo("/auth/revoke");
        assertThat(revokeBody).contains("token=" + REFRESH_TOKEN);
        assertThat(revokeBody).contains("token_type_hint=refresh_token");
        assertThat(revokeBody).contains("client_id=" + CLIENT_ID);
        assertThat(revokeBody).contains("client_secret=");
        // client_secret 자리에는 우리가 서명한 JWT 가 들어간다 — 고정 문자열이 아니다
        assertThat(validationBody).doesNotContain("client_secret=&");
        assertThat(revokeBody).doesNotContain("client_secret=&");
    }

    @Test
    void throwsWhenAppleRejects() {
        server.enqueue(jsonResponse("""
                {"access_token":"apple-access-token","token_type":"Bearer","expires_in":3600}
                """));
        server.enqueue(new MockResponse().setResponseCode(400));

        assertError(AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE, () -> client(pem).revoke(REFRESH_TOKEN));
    }

    @Test
    void doesNotRevokeWhenStoredRefreshTokenIsInvalid() {
        server.enqueue(jsonResponse(400, """
                {"error":"invalid_grant"}
                """));

        assertError(AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE, () -> client(pem).revoke(REFRESH_TOKEN));

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mapsServerErrorAsAppleServerError() {
        server.enqueue(jsonResponse("""
                {"access_token":"apple-access-token","token_type":"Bearer","expires_in":3600}
                """));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertError(AppleApiErrorCode.APPLE_API_SERVER_ERROR, () -> client(pem).revoke(REFRESH_TOKEN));
    }

    @Test
    void mapsConnectionFailureAsNetworkError() throws IOException {
        server.shutdown();

        assertError(AppleApiErrorCode.APPLE_API_NETWORK_ERROR, () -> client(pem).revoke(REFRESH_TOKEN));
    }

    @Test
    void skipsWhenNotConfigured() {
        // .p8 이 없다 — 탈퇴를 막지 않는다
        assertThatCode(() -> client(null).revoke(REFRESH_TOKEN)).doesNotThrowAnyException();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void skipsWhenNoStoredToken() {
        // 이 기능이 붙기 전에 가입한 사용자다 — 끊을 토큰이 없다
        assertThatCode(() -> client(pem).revoke(null)).doesNotThrowAnyException();
        assertThat(server.getRequestCount()).isZero();
    }

    private void assertError(AppleApiErrorCode expected, Runnable callable) {
        assertThatThrownBy(callable::run)
                .isInstanceOfSatisfying(AppleApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .withFailMessage("Expected %s but cause was %s", expected, exception.getCause())
                                .isEqualTo(expected));
    }

    private AppleRevokeClient client(String privateKey) {
        AppleAuthProperties properties = new AppleAuthProperties(
                "https://appleid.apple.com/auth/keys",
                "https://appleid.apple.com",
                CLIENT_ID,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                "TEAM123456",
                "KEY7890123",
                privateKey,
                server.url("/auth/token").toString(),
                server.url("/auth/revoke").toString()
        );
        return new AppleRevokeClient(properties, new AppleClientSecretGenerator(properties));
    }

    private MockResponse jsonResponse(String body) {
        return jsonResponse(200, body);
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
