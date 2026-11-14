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

import com.momentory.letter.application.LetterGateProperties;
import com.momentory.letter.application.RiskJudgment;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.infrastructure.ai.GeminiApiClient;
import com.momentory.retrospect.infrastructure.ai.GeminiApiProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 판정자 — 판정자 모델로 부르고, 실패 · 모르는 값은 판정 없음(empty)으로 돌려준다. */
class GeminiRiskJudgeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private MockWebServer server;
    private GeminiRiskJudge judge;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        GeminiApiProperties properties = new GeminiApiProperties(server.url("/").toString(), "test-key",
                "gemini-flash-lite-latest", 0.7, Duration.ofSeconds(1), Duration.ofSeconds(5), "e");
        GeminiApiClient client = new GeminiApiClient(RestClient.builder().baseUrl(properties.baseUrl()).build(),
                mapper, e -> { }, properties);
        judge = new GeminiRiskJudge(client, new LetterGateProperties(LetterGateProperties.Mode.ON, false,
                LetterPolicy.GENTLE, "judge-model-x", "verifier-model-y", 7, 400, Duration.ofSeconds(4)));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void withModelUsesGivenModelInUri() throws InterruptedException {
        server.enqueue(ok(clean()));

        Optional<RiskJudgment> result = judge.judge(1L, "오늘은 산책을 했다.", List.of("산책했어요"), List.of());

        assertThat(result).isPresent();
        String path = server.takeRequest().getPath();
        assertThat(path).contains("/models/judge-model-x:generateContent");
    }

    @Test
    void parsesThreeColumnsAndEvidence() {
        server.enqueue(ok(Map.of("todayCurrent", "none", "todayMentioned", "risk", "historyRisk", "risk",
                "trajectory", "recovery", "uncertain", false,
                "evidence", List.of(Map.of("field", "todayMentioned", "source", "today", "quote", "예전엔 살기 싫었는데")))));

        RiskJudgment j = judge.judge(1L, "일기", List.of("예전엔 살기 싫었는데 요즘은 괜찮아요"),
                List.of(new GeminiRiskJudge.PastDiary(LocalDate.of(2026, 11, 3), "다 끝내고 싶다"))).orElseThrow();

        assertThat(j.todayCurrent()).isEqualTo(SafetyLevel.NONE);
        assertThat(j.todayMentioned()).isEqualTo(SafetyLevel.RISK);
        assertThat(j.trajectory()).isEqualTo(RiskJudgment.Trajectory.RECOVERY);
        assertThat(j.evidence()).extracting(RiskJudgment.Evidence::quote).containsExactly("예전엔 살기 싫었는데");
    }

    @Test
    void emptyResponseIsUnusable() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(judge.judge(1L, "일기", List.of(), List.of())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(2); // 다시 불러 볼 만한 실패라 한 번 더
    }

    @Test
    void safetyBlockedRequestIsEmptyWithoutRetry() {
        // 안전 필터가 판정 요청 자체를 막으면 판정 없음 — 결정표 D4 로 가서 적어도 GENTLE(LetterSafetyGateTest T4 · T5)
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));

        assertThat(judge.judge(1L, "일기", List.of(), List.of())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void unknownLevelIsUnusable() {
        Map<String, Object> raw = new java.util.HashMap<>(clean());
        raw.put("todayCurrent", "high");
        server.enqueue(ok(raw));

        assertThat(judge.judge(1L, "일기", List.of(), List.of())).isEmpty();
    }

    @Test
    void pastDiariesGoToTheirOwnSectionWithDates() {
        String prompt = GeminiRiskJudge.prompt("오늘 일기", List.of("말"),
                List.of(new GeminiRiskJudge.PastDiary(LocalDate.of(2026, 11, 3), "지난 일기")));
        assertThat(prompt).contains("[지난 일기 — 날짜순, 앞부분만]\n[2026-11-03] 지난 일기");
    }

    private static Map<String, Object> clean() {
        return Map.of("todayCurrent", "none", "todayMentioned", "none", "historyRisk", "none",
                "trajectory", "stable", "uncertain", false, "evidence", List.of());
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
}
