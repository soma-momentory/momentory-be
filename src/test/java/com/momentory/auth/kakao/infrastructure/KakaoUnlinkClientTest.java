package com.momentory.auth.kakao.infrastructure;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoUnlinkClientTest {

    private static final Long KAKAO_APP_ID = 123456789L;
    private static final String ADMIN_KEY = "test-admin-key";

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
    void unlinksUserWithAdminKeyAndProviderUserId() throws Exception {
        server.enqueue(jsonResponse(200, "{\"id\":1001}"));

        client().unlink("1001");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/user/unlink");
        assertThat(request.getHeader("Authorization")).isEqualTo("KakaoAK " + ADMIN_KEY);
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.getBody().readUtf8())
                .contains("target_id_type=user_id")
                .contains("target_id=1001");
    }

    @Test
    void treatsAlreadyUnlinkedUserAsSuccess() {
        server.enqueue(jsonResponse(400, "{\"code\":-101,\"msg\":\"NotRegisteredUserException\"}"));

        assertThatCode(() -> client().unlink("1001")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedUnlinkResponse() {
        server.enqueue(jsonResponse(200, "{\"id\":2002}"));

        assertError(KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE, () -> client().unlink("1001"));
    }

    @Test
    void mapsKakaoServerFailure() {
        server.enqueue(jsonResponse(500, "{}"));

        assertError(KakaoApiErrorCode.KAKAO_API_SERVER_ERROR, () -> client().unlink("1001"));
    }

    @Test
    void mapsConnectionFailureAsNetworkError() {
        assertError(KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR, () -> unavailableClient().unlink("1001"));
    }

    private KakaoUnlinkClient client() {
        return client(server.url("/").toString());
    }

    private KakaoUnlinkClient unavailableClient() {
        return client("http://127.0.0.1:1");
    }

    private KakaoUnlinkClient client(String baseUrl) {
        KakaoApiProperties properties = new KakaoApiProperties(
                baseUrl,
                KAKAO_APP_ID,
                ADMIN_KEY,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        KakaoApiClientConfiguration configuration = new KakaoApiClientConfiguration();
        return new KakaoUnlinkClient(
                configuration.kakaoRestClient(properties),
                properties,
                JsonMapper.builder().build()
        );
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private void assertError(KakaoApiErrorCode expectedCode, ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOfSatisfying(KakaoApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedCode)
                );
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
