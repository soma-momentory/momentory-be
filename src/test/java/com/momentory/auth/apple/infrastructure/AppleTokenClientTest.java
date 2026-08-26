package com.momentory.auth.apple.infrastructure;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Apple authorization code 교환의 요청·성공·진단 실패 경로를 고정한다. */
@ExtendWith(OutputCaptureExtension.class)
class AppleTokenClientTest {

    private static final String CLIENT_ID = "kr.momentory.app";
    private static final String AUTHORIZATION_CODE = "apple-authorization-code-secret";
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
    void exchangesAuthorizationCodeForRefreshToken() throws Exception {
        server.enqueue(jsonResponse(200, "{\"refresh_token\":\"" + REFRESH_TOKEN + "\"}"));

        String result = client(pem).exchangeRefreshToken(AUTHORIZATION_CODE);

        assertThat(result).isEqualTo(REFRESH_TOKEN);
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(request.getPath()).isEqualTo("/auth/token");
        assertThat(body).contains("grant_type=authorization_code");
        assertThat(body).contains("code=" + AUTHORIZATION_CODE);
        assertThat(body).contains("client_id=" + CLIENT_ID);
        assertThat(body).contains("client_secret=");
    }

    @Test
    void recordsAppleErrorCodeWithoutLoggingSecrets(CapturedOutput output) {
        server.enqueue(jsonResponse(400, """
                {"error":"invalid_client","error_description":"do-not-log-this-description"}
                """));

        String result = client(pem).exchangeRefreshToken(AUTHORIZATION_CODE);

        assertThat(result).isNull();
        assertThat(output).contains("reason=apple_rejected");
        assertThat(output).contains("status=400");
        assertThat(output).contains("apple_error=invalid_client");
        assertThat(output).doesNotContain(AUTHORIZATION_CODE);
        assertThat(output).doesNotContain("do-not-log-this-description");
    }

    @Test
    void recordsMissingAuthorizationCode(CapturedOutput output) {
        String result = client(pem).exchangeRefreshToken("  ");

        assertThat(result).isNull();
        assertThat(output).contains("reason=authorization_code_missing");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void recordsMissingRevokeCredentials(CapturedOutput output) {
        String result = client(null).exchangeRefreshToken(AUTHORIZATION_CODE);

        assertThat(result).isNull();
        assertThat(output).contains("reason=revoke_credentials_missing");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void recordsResponseWithoutRefreshToken(CapturedOutput output) {
        server.enqueue(jsonResponse(200, "{\"access_token\":\"unused\"}"));

        String result = client(pem).exchangeRefreshToken(AUTHORIZATION_CODE);

        assertThat(result).isNull();
        assertThat(output).contains("reason=refresh_token_missing");
        assertThat(output).doesNotContain("unused");
    }

    private AppleTokenClient client(String privateKey) {
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
        return new AppleTokenClient(properties, new AppleClientSecretGenerator(properties));
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
