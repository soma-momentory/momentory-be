package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.application.metering.CallLog;
import com.momentory.retrospect.application.metering.LlmCallRecorded;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini REST 직접 호출 클라이언트 단위 검증 — 요청 형태·구조화 출력 파싱·usage 집계·예외 무전파.
 * 실제 Gemini 없이 MockWebServer 로 응답을 흉내 낸다.
 */
class GeminiApiClientTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private MockWebServer server;
    private List<Object> events;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        events = new ArrayList<>();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesStructuredOutputAndRecordsUsage() throws Exception {
        String inner = mapper.writeValueAsString(new UnderstandingCheck(
                "답변이 막혀 불안했던 것 같네요.", "면접에서 말이 막힘", "none", List.of(),
                false, false, false));
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", inner))))),
                "usageMetadata", Map.of("promptTokenCount", 100, "candidatesTokenCount", 20,
                        "cachedContentTokenCount", 5),
                "modelVersion", "gemini-flash-lite-latest"))));

        Optional<UnderstandingCheck> result = client().generate(request("이해 확인 부탁"),
                UnderstandingCheck.class);

        assertThat(result).isPresent();
        assertThat(result.get().reflection()).isEqualTo("답변이 막혀 불안했던 것 같네요.");
        assertThat(result.get().situation()).isEqualTo("면접에서 말이 막힘");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath())
                .isEqualTo("/v1beta/models/gemini-flash-lite-latest:generateContent");
        assertThat(recorded.getHeader("x-goog-api-key")).isEqualTo("test-key");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("systemInstruction");
        assertThat(body).contains("responseSchema");
        assertThat(body).contains("application/json");
        assertThat(body).contains("이해 확인 부탁");

        assertThat(events).hasSize(1);
        CallLog log = ((LlmCallRecorded) events.get(0)).log();
        assertThat(log.inTokens()).isEqualTo(100);
        assertThat(log.outTokens()).isEqualTo(20);
        assertThat(log.cachedTokens()).isEqualTo(5);
    }

    @Test
    void returnsEmptyOnServerErrorWithoutThrowing() {
        server.enqueue(new MockResponse().setResponseCode(500));

        Optional<UnderstandingCheck> result = client().generate(request("q"), UnderstandingCheck.class);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoCandidateText() throws Exception {
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(),
                "usageMetadata", Map.of("promptTokenCount", 1, "candidatesTokenCount", 0)))));

        Optional<UnderstandingCheck> result = client().generate(request("q"), UnderstandingCheck.class);

        assertThat(result).isEmpty();
    }

    private GeminiApiClient client() {
        GeminiApiProperties properties = new GeminiApiProperties(
                server.url("/").toString(), "test-key", "gemini-flash-lite-latest",
                0.7, Duration.ofSeconds(1), Duration.ofSeconds(5));
        RestClient restClient = new GeminiApiClientConfiguration().geminiRestClient(properties);
        return new GeminiApiClient(restClient, mapper, events::add, properties);
    }

    private LlmRequest request(String user) {
        return LlmRequest.of(new RetrospectState("sess-1"), LlmRole.G1, "시스템 프롬프트", user);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
