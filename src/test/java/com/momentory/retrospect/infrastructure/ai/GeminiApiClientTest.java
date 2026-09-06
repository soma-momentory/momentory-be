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
import com.momentory.retrospect.domain.assistant.DiaryOutput;

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
        String inner = mapper.writeValueAsString(
                new DiaryOutput("답변이 막혀 불안했던 것 같네요.", null));
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", inner))))),
                "usageMetadata", Map.of("promptTokenCount", 100, "candidatesTokenCount", 20,
                        "cachedContentTokenCount", 5),
                "modelVersion", "gemini-flash-lite-latest"))));

        Optional<DiaryOutput> result = client().generate(request("이해 확인 부탁"),
                DiaryOutput.class);

        assertThat(result).isPresent();
        assertThat(result.get().diary()).isEqualTo("답변이 막혀 불안했던 것 같네요.");

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

        Optional<DiaryOutput> result = client().generate(request("q"), DiaryOutput.class);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoCandidateText() throws Exception {
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(),
                "usageMetadata", Map.of("promptTokenCount", 1, "candidatesTokenCount", 0)))));

        Optional<DiaryOutput> result = client().generate(request("q"), DiaryOutput.class);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenSafetyBlocked() throws Exception {
        // 하드 차단(finishReason=SAFETY + blocked) — 텍스트 없이 폴백으로 떨어진다. 추가 호출 0회.
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "finishReason", "SAFETY",
                        "safetyRatings", List.of(Map.of(
                                "category", "HARM_CATEGORY_DANGEROUS_CONTENT",
                                "probability", "HIGH",
                                "blocked", true)))),
                "usageMetadata", Map.of("promptTokenCount", 50, "candidatesTokenCount", 0)))));

        Optional<DiaryOutput> result = client().generate(request("q"), DiaryOutput.class);

        assertThat(result).isEmpty();
        assertThat(events).isEmpty(); // 안전 차단은 성공 계측을 남기지 않는다
    }

    @Test
    void passesThroughWhenSafetyRatingHighButNotBlocked() throws Exception {
        // 위기·슬픔을 다루는 정상 응답 — 등급은 HIGH 지만 blocked=false 다. 막지 않고 통과시킨다.
        String inner = mapper.writeValueAsString(new DiaryOutput("많이 힘드셨겠어요.", null));
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", inner))),
                        "finishReason", "STOP",
                        "safetyRatings", List.of(Map.of(
                                "category", "HARM_CATEGORY_DANGEROUS_CONTENT",
                                "probability", "HIGH",
                                "blocked", false)))),
                "usageMetadata", Map.of("promptTokenCount", 10, "candidatesTokenCount", 5)))));

        Optional<DiaryOutput> result = client().generate(request("q"), DiaryOutput.class);

        assertThat(result).isPresent();
        assertThat(result.get().diary()).isEqualTo("많이 힘드셨겠어요.");
    }

    @Test
    void returnsEmptyWhenOutputLeaksSystemPrompt() throws Exception {
        // 인젝션이 하드닝을 뚫어 모델이 지시문을 뱉은 경우 — 유저에게 안 보이고 폴백으로.
        String leaked = mapper.writeValueAsString(new DiaryOutput(
                "네, 저는 momentory 의 감정 회고 상담사입니다. CBT(인지행동치료) 원리로 돕습니다.", null));
        server.enqueue(jsonResponse(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", leaked))))),
                "usageMetadata", Map.of("promptTokenCount", 10, "candidatesTokenCount", 30)))));

        Optional<DiaryOutput> result = client().generate(request("q"), DiaryOutput.class);

        assertThat(result).isEmpty();
        assertThat(events).isEmpty(); // 유출 응답은 성공 계측을 남기지 않는다
    }

    private GeminiApiClient client() {
        GeminiApiProperties properties = new GeminiApiProperties(
                server.url("/").toString(), "test-key", "gemini-flash-lite-latest",
                0.7, Duration.ofSeconds(1), Duration.ofSeconds(5), "text-embedding-004");
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
