package com.momentory.auth.kakao.infrastructure;

import com.momentory.auth.kakao.application.KakaoUserInfo;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoApiClientTest {

    private static final Long KAKAO_APP_ID = 123456789L;

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsKakaoUserInfoForValidToken() throws Exception {
        enqueueTokenInfo(1001L, KAKAO_APP_ID, 3600L);
        enqueueUserInfo(1001L, "user@example.com");

        KakaoUserInfo userInfo = client(Duration.ofSeconds(1)).getUserInfo("kakao-access-token");

        assertThat(userInfo.providerUserId()).isEqualTo("1001");
        assertThat(userInfo.email()).isEqualTo("user@example.com");
        assertRequest(server.takeRequest(), "/v1/user/access_token_info");
        assertRequest(server.takeRequest(), "/v2/user/me");
    }

    @Test
    void returnsNullEmailWhenKakaoDoesNotProvideOne() {
        enqueueTokenInfo(1001L, KAKAO_APP_ID, 3600L);
        server.enqueue(jsonResponse("""
                {"id":1001,"kakao_account":{}}
                """));

        KakaoUserInfo userInfo = client(Duration.ofSeconds(1)).getUserInfo("kakao-access-token");

        assertThat(userInfo.email()).isNull();
    }

    @Test
    void mapsInvalidKakaoToken() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertError(KakaoApiErrorCode.INVALID_ACCESS_TOKEN, () -> client(Duration.ofSeconds(1))
                .getUserInfo("invalid-token"));
    }

    @Test
    void rejectsTokenFromAnotherKakaoApplication() {
        enqueueTokenInfo(1001L, 999L, 3600L);

        assertError(KakaoApiErrorCode.APP_ID_MISMATCH, () -> client(Duration.ofSeconds(1))
                .getUserInfo("kakao-access-token"));
    }

    @Test
    void rejectsDifferentTokenAndUserIds() {
        enqueueTokenInfo(1001L, KAKAO_APP_ID, 3600L);
        enqueueUserInfo(2002L, null);

        assertError(KakaoApiErrorCode.USER_ID_MISMATCH, () -> client(Duration.ofSeconds(1))
                .getUserInfo("kakao-access-token"));
    }

    @Test
    void mapsKakaoServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertError(KakaoApiErrorCode.KAKAO_API_SERVER_ERROR, () -> client(Duration.ofSeconds(1))
                .getUserInfo("kakao-access-token"));
    }

    @Test
    void mapsConnectionFailureAsNetworkError() {
        assertError(KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR, () -> unavailableClient()
                .getUserInfo("kakao-access-token"));
    }

    @Test
    void mapsMissingRequiredResponseFieldAsUnexpectedResponse() {
        server.enqueue(jsonResponse("""
                {"app_id":123456789,"expires_in":3600}
                """));

        assertError(KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE, () -> client(Duration.ofSeconds(1))
                .getUserInfo("kakao-access-token"));
    }

    private KakaoApiClient client(Duration readTimeout) {
        KakaoApiProperties properties = new KakaoApiProperties(
                server.url("/").toString(),
                KAKAO_APP_ID,
                "test-admin-key",
                Duration.ofSeconds(1),
                readTimeout
        );
        KakaoApiClientConfiguration configuration = new KakaoApiClientConfiguration();
        return new KakaoApiClient(configuration.kakaoRestClient(properties), properties);
    }

    private KakaoApiClient unavailableClient() {
        KakaoApiProperties properties = new KakaoApiProperties(
                "http://127.0.0.1:1",
                KAKAO_APP_ID,
                "test-admin-key",
                Duration.ofMillis(100),
                Duration.ofMillis(100)
        );
        KakaoApiClientConfiguration configuration = new KakaoApiClientConfiguration();
        return new KakaoApiClient(configuration.kakaoRestClient(properties), properties);
    }

    private void enqueueTokenInfo(Long userId, Long appId, Long expiresIn) {
        server.enqueue(jsonResponse("""
                {"id":%d,"app_id":%d,"expires_in":%d}
                """.formatted(userId, appId, expiresIn)));
    }

    private void enqueueUserInfo(Long userId, String email) {
        String kakaoAccount = email == null ? "{}" : "{\"email\":\"" + email + "\"}";
        server.enqueue(jsonResponse("""
                {"id":%d,"kakao_account":%s}
                """.formatted(userId, kakaoAccount)));
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private void assertRequest(RecordedRequest request, String path) {
        assertThat(request.getPath()).isEqualTo(path);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer kakao-access-token");
    }

    private void assertError(KakaoApiErrorCode expectedCode, ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOfSatisfying(KakaoApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .withFailMessage("Expected %s but cause was %s", expectedCode, exception.getCause())
                                .isEqualTo(expectedCode)
                );
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
