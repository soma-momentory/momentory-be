package com.momentory.letter.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.momentory.letter.application.LetterGateProperties;
import com.momentory.letter.application.LetterVerification;
import com.momentory.letter.application.LetterVerification.Outcome;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.retrospect.infrastructure.ai.GeminiApiClient;
import com.momentory.retrospect.infrastructure.ai.GeminiApiProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 생성 뒤 검증기 — 결정적 검사, 지어낸 위반 거르기, 실패를 「검증 불가」로. */
class GeminiLetterVerifierTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private MockWebServer server;
    private GeminiLetterVerifier verifier;

    private static final GeminiLetterVerifier.Draft LETTER = new GeminiLetterVerifier.Draft("발표 전에 손이 떨렸어요",
            "떨렸던 건 그만큼 잘하고 싶었기 때문이었을지도 몰라요.", "오늘 밤엔 발표 이야기부터 물어볼게요.", List.of());

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        GeminiApiProperties properties = new GeminiApiProperties(server.url("/").toString(), "test-key",
                "gemini-flash-lite-latest", 0.7, Duration.ofSeconds(1), Duration.ofSeconds(5), "e");
        GeminiApiClient client = new GeminiApiClient(RestClient.builder().baseUrl(properties.baseUrl()).build(),
                mapper, e -> { }, properties);
        verifier = new GeminiLetterVerifier(client, new LetterGateProperties(LetterGateProperties.Mode.ON, false,
                LetterPolicy.GENTLE, "judge-model-x", "verifier-model-y", 7, 400, Duration.ofSeconds(4)));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void passWhenNoViolationsAndUsesVerifierModel() throws InterruptedException {
        server.enqueue(ok(Map.of("violations", List.of())));

        assertThat(verifier.verify(materials(), LetterPolicy.NORMAL, LETTER, List.of()).outcome())
                .isEqualTo(Outcome.PASS);
        assertThat(server.takeRequest().getPath()).contains("/models/verifier-model-y:generateContent");
    }

    @Test
    void majorR1ClosesQuietAndMinorDoesNot() {
        server.enqueue(ok(Map.of("violations", List.of(Map.of("rule", "R1", "severity", "major",
                "target", "insight", "quote", "잘하고 싶었기 때문")))));
        LetterVerification major = verifier.verify(materials(), LetterPolicy.NORMAL, LETTER, List.of());
        assertThat(major.outcome()).isEqualTo(Outcome.MAJOR);
        assertThat(major.closesQuiet()).isTrue();

        server.enqueue(ok(Map.of("violations", List.of(Map.of("rule", "R3", "severity", "minor",
                "target", "insight", "quote", "잘하고 싶었기 때문", "fix", "넘겨짚지 않는다")))));
        LetterVerification minor = verifier.verify(materials(), LetterPolicy.NORMAL, LETTER, List.of());
        assertThat(minor.outcome()).isEqualTo(Outcome.MINOR);
        assertThat(minor.closesQuiet()).isFalse();
    }

    @Test
    void fabricatedQuoteMakesVerificationUnusable() {
        server.enqueue(ok(Map.of("violations", List.of(Map.of("rule", "R3", "severity", "minor",
                "target", "insight", "quote", "편지에 없는 문장")))));

        assertThat(verifier.verify(materials(), LetterPolicy.NORMAL, LETTER, List.of()).outcome())
                .isEqualTo(Outcome.UNUSABLE);
    }

    @Test
    void failedCallIsUnusable() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThat(verifier.verify(materials(), LetterPolicy.NORMAL, LETTER, List.of()).outcome())
                .isEqualTo(Outcome.UNUSABLE);
    }

    @Test
    void wrongHelplineIsMajorR8EvenWhenModelPasses() {
        server.enqueue(ok(Map.of("violations", List.of())));
        GeminiLetterVerifier.Draft draft = new GeminiLetterVerifier.Draft(null,
                "힘들 땐 상담전화 1393 으로 이야기해 보세요.", null, List.of());

        LetterVerification v = verifier.verify(materials(), LetterPolicy.GENTLE, draft, List.of());

        assertThat(v.outcome()).isEqualTo(Outcome.MAJOR);
        assertThat(v.violations()).extracting(LetterVerification.Violation::rule).containsExactly("R8");
    }

    @Test
    void deterministicChecks() {
        assertThat(GeminiLetterVerifier.deterministic(new GeminiLetterVerifier.Draft(null,
                "자살예방 상담전화 109 는 24시간 열려 있어요.", null, List.of()))).isEmpty();
        assertThat(GeminiLetterVerifier.deterministic(new GeminiLetterVerifier.Draft(null,
                "우울증일 수도 있어요.", null, List.of()))).extracting(LetterVerification.Violation::rule)
                .containsExactly("R2");
        // 인용은 사용자가 한 말 그대로라 진단명이 있어도 편지가 단정한 것이 아니다.
        assertThat(GeminiLetterVerifier.deterministic(new GeminiLetterVerifier.Draft("우울증 약을 받았어요",
                "관점", null, List.of()))).isEmpty();
    }

    @Test
    @DisplayName("「상담 · 전화」 같은 말 없이 번호만 써도 허용 목록 밖이면 R8(나 실험에서 정규식을 빠져나간 꼴)")
    void wrongNumberWithoutKeyword() {
        for (String body : List.of("쓸쓸함이 깊어지는 밤엔 1577-0919로 걸어 이야기해도 돼요.",
                "긴장이 계속되면 직장인 마음 쉼터 1599-0199에서 도움받을 수 있어요.", "산에서 다치면 1190으로 바로 알려요.",
                "그때 선생님이 그리워지면 1577-0190에서 비슷한 분이 이야기를 들어 줘요.", "급하면 02-123-4567로 걸어요.")) {
            assertThat(GeminiLetterVerifier.deterministic(new GeminiLetterVerifier.Draft(null, body, null, List.of())))
                    .as(body).extracting(LetterVerification.Violation::rule).containsExactly("R8");
        }
    }

    @Test
    @DisplayName("허용된 번호와 단위가 붙은 수는 R8 이 아니다")
    void allowedNumbersAndQuantitiesAreNotR8() {
        for (String body : List.of("힘들면 109나 1577-0199에 이야기해도 돼요.", "급하면 112 · 119, 청소년은 1388이에요.",
                "100일 동안 1000원씩 모았어요.", "1999년에 태어났고 120분을 걸었어요.", "10:30에 2026-11-14 일정을 봐요.",
                "150명 앞에서 발표했어요.", "1등을 했어요.")) {
            assertThat(GeminiLetterVerifier.deterministic(new GeminiLetterVerifier.Draft(null, body, null, List.of())))
                    .as(body).isEmpty();
        }
    }

    @Test
    @DisplayName("검증기도 판정자와 같은 지난 일기를 받는다 — 여러 날 이야기를 일기에 없는 말(R4)로 보지 않게")
    void promptCarriesPastDiaries() {
        String prompt = GeminiLetterVerifier.prompt(materials(), LetterPolicy.NORMAL, LETTER,
                List.of(new GeminiRiskJudge.PastDiary(LocalDate.of(2026, 10, 24), "보리와 산책을 했다")));
        assertThat(prompt).contains("[지난 일기", "[2026-10-24] 보리와 산책을 했다");
        assertThat(GeminiLetterVerifier.prompt(materials(), LetterPolicy.NORMAL, LETTER, List.of()))
                .contains("[지난 일기 — 날짜순, 앞부분만]\n없음");
    }

    private static LetterMaterials materials() {
        return new LetterMaterials(1L, LocalDate.of(2026, 10, 26), "하늘", "오늘은 발표가 있었다.",
                List.of("발표 전에 손이 떨렸어요"), false, List.of(), List.of(), List.of(), null, List.of());
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
