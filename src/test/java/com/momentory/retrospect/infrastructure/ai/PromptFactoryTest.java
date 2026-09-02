package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * 히스토리 조립과 감정 추출 프롬프트 검증 (모델 비교 계획 §3.2~3.3).
 *
 * <p>여기서 지키려는 것: ⑴ 선택지 줄이 프롬프트에 새지 않는다, ⑵ 번호는 사용자 발화에만 붙는다
 * (AI 발화를 근거로 지목할 수 없게 하는 구조적 강제), ⑶ 예산 초과 시 <b>앞</b>이 아니라 중간을 접는다.
 */
class PromptFactoryTest {

    private final PromptFactory prompts =
            new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT);

    private static RetrospectState stateWithConversation() {
        RetrospectState state = new RetrospectState("sess-1");
        state.addUserMessage("오늘 발표에서 말이 막혔어요");
        state.addAssistantMessage("그때 어떤 기분이었어요?");
        state.addUserMessage("준비가 부족한 것 같아서 너무 불안했어요");
        return state;
    }

    @Test
    @DisplayName("번호는 사용자 발화에만 붙고, AI 발화는 번호 없이 남는다")
    void numbersUserUtterancesOnly() {
        String history = prompts.numberedHistory(stateWithConversation());

        assertThat(history).isEqualTo("""
                [U1] 오늘 발표에서 말이 막혔어요
                (바바) 그때 어떤 기분이었어요?
                [U2] 준비가 부족한 것 같아서 너무 불안했어요""");
    }

    @Test
    @DisplayName("선택지 줄은 히스토리에서 걷어낸다 — 감정·사건 판단에 노이즈다")
    void stripsOptionLines() {
        RetrospectState state = new RetrospectState("sess-2");
        state.addAssistantMessage("지금 가장 가까운 감정은 무엇인가요?\n  1. 불안함\n  2. 답답함 — 말이 안 통할 때");
        state.addUserMessage("불안함");

        String history = prompts.numberedHistory(state);

        assertThat(history).doesNotContain("1. 불안함").doesNotContain("2. 답답함");
        assertThat(history).isEqualTo("""
                (바바) 지금 가장 가까운 감정은 무엇인가요?
                [U1] 불안함""");
    }

    @Test
    @DisplayName("선택지만 있는 메시지는 통째로 빠진다 — 빈 화자 줄을 남기지 않는다")
    void dropsMessageThatIsOnlyOptions() {
        RetrospectState state = new RetrospectState("sess-3");
        state.addAssistantMessage("  1. 불안함\n  2. 답답함");
        state.addUserMessage("불안함");

        assertThat(prompts.numberedHistory(state)).isEqualTo("[U1] 불안함");
    }

    @Test
    @DisplayName("예산을 넘으면 앞머리(사건 도입부)를 남기고 중간을 접는다")
    void foldsMiddleNotHead() {
        PromptFactory tight = new PromptFactory(120, EmotionPromptVariant.ZERO_SHOT);
        RetrospectState state = new RetrospectState("sess-4");
        state.addUserMessage("오늘 발표가 있었어요");
        for (int i = 0; i < 10; i++) {
            state.addAssistantMessage("조금 더 들려줄래요? 그때 어떤 마음이었는지 궁금해요.");
            state.addUserMessage("그러니까 계속 곱씹게 되더라고요. 자꾸 생각이 나요.");
        }
        state.addUserMessage("지금은 좀 괜찮아졌어요");

        String folded = tight.recentHistory(state);

        assertThat(folded).startsWith("나: 오늘 발표가 있었어요");   // 도입부 보존
        assertThat(folded).endsWith("지금은 좀 괜찮아졌어요");        // 최근 대화 보존
        assertThat(folded).contains("…");                              // 가운데만 생략
    }

    @Test
    @DisplayName("예산 안이면 전문 그대로 둔다")
    void keepsHistoryWithinBudget() {
        RetrospectState state = stateWithConversation();

        assertThat(prompts.recentHistory(state)).isEqualTo(prompts.fullHistory(state));
    }

    @Test
    @DisplayName("감정 추출 프롬프트에 §3.2 규칙과 번호 붙은 대화가 함께 들어간다")
    void zeroShotPromptCarriesRulesAndNumberedHistory() {
        String prompt = prompts.emotionExtractPrompt(stateWithConversation());

        assertThat(prompt).contains("[U1] 오늘 발표에서 말이 막혔어요");
        assertThat(prompt).contains("바바(AI)의 발화는 사용자 감정의 근거로 쓰지 않습니다");
        assertThat(prompt).contains("다른 사람의 감정");
        assertThat(prompt).contains("intensity");
        assertThat(prompt).contains("before(사건 전)");
        assertThat(prompt).contains("anxious");        // 고정 10종 키
        assertThat(prompt).contains("최대 2개");        // 사건 상한
    }

    @Test
    @DisplayName("강도 안내는 1~4 이고 0 을 쓰지 말라고 한다 — 감정이 없으면 항목 자체를 넣지 않는다")
    void intensityScaleExcludesZero() {
        String prompt = prompts.emotionExtractPrompt(stateWithConversation());

        assertThat(prompt).contains("1~4 정수");
        assertThat(prompt).contains("0 을 쓰지 말고");
    }

    // ── 프롬프트 변형 (계획 §3.5) ────────────────────────────────────

    private static PromptFactory with(EmotionPromptVariant variant) {
        return new PromptFactory(2000, variant);
    }

    @Test
    @DisplayName("변형 간 차이는 예시 블록뿐이다 — 규칙이 함께 바뀌면 효과를 예시에 귀속시킬 수 없다")
    void variantsShareTheSameRules() {
        RetrospectState state = stateWithConversation();
        String rules = """
                [규칙]
                1. 사용자가 명시하지 않은 감정은 추측하지 않습니다.""";

        for (EmotionPromptVariant v : List.of(EmotionPromptVariant.ZERO_SHOT,
                EmotionPromptVariant.FEW_SHOT, EmotionPromptVariant.FEW_SHOT_BOUNDARY)) {
            String prompt = with(v).emotionExtractPrompt(state);
            assertThat(prompt).as("%s 규칙 블록", v).contains(rules);
            assertThat(prompt).as("%s 강도 안내", v).contains("1~4 정수");
            assertThat(prompt).as("%s 대화", v).contains("[U1] 오늘 발표에서 말이 막혔어요");
        }
    }

    @Test
    @DisplayName("zero-shot 에는 예시가 없다")
    void zeroShotHasNoExamples() {
        assertThat(prompts.emotionExtractPrompt(stateWithConversation())).doesNotContain("[예시]");
    }

    @Test
    @DisplayName("few-shot 은 분포 점검에서 드러난 두 구멍(강도 4·암묵적 before)을 예시로 겨눈다")
    void fewShotAnchorsIntensityAndBefore() {
        String prompt = with(EmotionPromptVariant.FEW_SHOT)
                .emotionExtractPrompt(stateWithConversation());

        assertThat(prompt).contains("[예시]");
        assertThat(prompt).contains("\"intensity\":4");            // 압도적 강도 앵커
        assertThat(prompt).contains("\"phase\":\"before\"");        // 암묵적 before 앵커
        assertThat(prompt).contains("전날부터");
        assertThat(prompt).contains("\"emotions\":[]");             // 감정 없음 앵커
        assertThat(prompt).contains("\"intensity\":1");             // 약한 강도 앵커
    }

    @Test
    @DisplayName("경계 few-shot 은 화남·답답·막막과 부정 표현을 보여준다")
    void boundaryFewShotCoversConfusablePairs() {
        String prompt = with(EmotionPromptVariant.FEW_SHOT_BOUNDARY)
                .emotionExtractPrompt(stateWithConversation());

        assertThat(prompt).contains("angry").contains("frustrated").contains("stuck");
        assertThat(prompt).contains("성질이 난 건 아니에요");   // 부정 표현
        assertThat(prompt).doesNotContain("전날부터");        // 강도·시점 앵커는 섞지 않는다
    }

    @Test
    @DisplayName("결합 변형은 경계 예시와 강도 앵커를 함께 준다")
    void combinedCarriesBothAxes() {
        String prompt = with(EmotionPromptVariant.FEW_SHOT_COMBINED)
                .emotionExtractPrompt(stateWithConversation());

        assertThat(prompt).contains("전날부터");                  // 시점 앵커 (FEW_SHOT)
        assertThat(prompt).contains("\"intensity\":4");            // 강도 앵커 (FEW_SHOT)
        assertThat(prompt).contains("angry, intensity");         // 경계 + 강도 (결합 전용 블록)
        assertThat(prompt).contains("stuck, intensity");
    }

    @Test
    @DisplayName("프롬프트 예시가 challenge 세트의 발화를 쓰면 안 된다 — 모델에 답을 보여주는 셈이다")
    void examplesMustNotLeakEvaluationUtterances() throws Exception {
        Path probes = Path.of("eval/challenge/boundary-probes.txt");
        assumeTrue(Files.exists(probes), "challenge 세트가 있을 때만 검사한다");
        List<String> utterances = Files.readAllLines(probes).stream()
                .filter(l -> l.startsWith("U:"))
                .map(l -> l.substring(2).strip())
                .filter(l -> l.length() >= 8)
                .toList();

        for (EmotionPromptVariant v : List.of(EmotionPromptVariant.ZERO_SHOT,
                EmotionPromptVariant.FEW_SHOT, EmotionPromptVariant.FEW_SHOT_BOUNDARY,
                EmotionPromptVariant.FEW_SHOT_COMBINED)) {
            String prompt = with(v).emotionExtractPrompt(stateWithConversation());
            for (String u : utterances) {
                assertThat(prompt).as("%s 프롬프트가 평가 발화를 포함: %s", v, u).doesNotContain(u);
            }
        }
    }

    @Test
    @DisplayName("아직 구현하지 않은 2단계 변형은 조용히 zero-shot 으로 떨어지지 않고 실패한다")
    void unimplementedVariantFails() {
        assertThatThrownBy(() -> with(EmotionPromptVariant.TWO_STAGE)
                .emotionExtractPrompt(stateWithConversation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWO_STAGE");
    }
}
