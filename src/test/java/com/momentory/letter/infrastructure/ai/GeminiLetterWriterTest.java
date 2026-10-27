package com.momentory.letter.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.momentory.letter.domain.LetterMaterials;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.infrastructure.RetrospectDomainConfiguration;
import com.momentory.retrospect.infrastructure.ai.GeminiApiClient;
import com.momentory.retrospect.infrastructure.ai.GeminiApiProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 편지 호출의 재시도 규칙 — 뒤에서는 429 · 5xx 에만 1회 더, 열 때는 한 번뿐. */
class GeminiLetterWriterTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private MockWebServer server;
    private GeminiLetterWriter writer;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        GeminiApiProperties properties = new GeminiApiProperties(server.url("/").toString(), "test-key",
                "gemini-flash-lite-latest", 0.7, Duration.ofSeconds(1), Duration.ofSeconds(5), "e");
        RestClient restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        NeedActionCatalog catalog = new RetrospectDomainConfiguration().needActionCatalog(mapper);
        writer = new GeminiLetterWriter(new GeminiApiClient(restClient, mapper, e -> { }, properties), catalog, meters);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void backgroundRetriesOnceAfterServerError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(ok(Map.of("quote", "떨렸어요", "insight", "관점", "tonightQuestion", "질문")));

        Optional<GeminiLetterWriter.Body> body = writer.writeBody(materials());

        assertThat(body).map(GeminiLetterWriter.Body::insight).contains("관점");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(meters.get("letter.gemini.retry").tag("role", "AI-LETTER").counter().count()).isEqualTo(1);
    }

    @Test
    void backgroundDoesNotRetrySafetyBlock() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "SAFETY"))))));

        assertThat(writer.writeBody(materials())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void openCallsOnlyOnce() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(ok(Map.of("insight", "관점")));

        assertThat(writer.writeBodyOnOpen(materials())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void bodyPromptKeepsAgeGenderAndInterestsOut() throws Exception {
        server.enqueue(ok(Map.of("insight", "관점")));

        writer.writeBody(materials());

        String sent = server.takeRequest().getBody().readUtf8();
        assertThat(sent).contains("발표 전에 손이 떨렸어요").contains("팀 회의 (했음)");
        assertThat(sent).doesNotContain("나이").doesNotContain("성별").doesNotContain("관심");
    }

    private MockResponse ok(Map<String, Object> inner) {
        try {
            return new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody(mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("content",
                            Map.of("parts", List.of(Map.of("text", mapper.writeValueAsString(inner)))))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LetterMaterials materials() {
        return new LetterMaterials(1L, LocalDate.of(2026, 10, 26), "하늘", "오늘은 발표가 있었다.",
                List.of("발표 전에 손이 떨렸어요"), false, List.of(), null, List.of("팀 회의 (했음)"),
                List.of(), null);
    }
}
