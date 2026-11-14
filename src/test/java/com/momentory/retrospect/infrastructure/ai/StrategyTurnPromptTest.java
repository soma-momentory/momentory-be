package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

class StrategyTurnPromptTest {

    private final PromptFactory prompts = new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT);

    private String prompt(TurnStrategy strategy) {
        RetrospectState state = new RetrospectState("s1");
        state.addAssistantMessage("오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?");
        state.addUserMessage("발표 망쳐서 허무해.");
        return prompts.strategyTurnPrompt(state, "발표 망쳐서 허무해.", strategy);
    }

    @Test
    void leavesWhatToAskToTheWriterWithOnePrinciple() {
        String prompt = prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false));

        assertThat(prompt).contains("탐색:", "질문: 해도 됩니다.", "한 걸음만 더 안쪽으로", "방금 짚은", "발표 망쳐서 허무해.",
                "이미 말한 것을 다시 묻지 않습니다", "이유를 이미 말했는데 이유를 또 묻지 않습니다",
                "언제 마무리할지는 서버가 정합니다");
        // 옛 고정 순서나 단계별로 정해 둔 질문은 없다.
        assertThat(prompt).doesNotContain("[판단 순서와 질문 목적]", "최대 5턴", "둘 중 고를 수 있는");
    }

    @Test
    void asksTheWriterToReadTheFlowItAloneCanSee() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("newTopic", "앞 이야기의 감정이나 사정을 새 이야기에 끌어오지 않습니다",
                        "nothingMoreToAsk", "빈칸을 다 채우는 것이 목표가 아닙니다");
    }

    @Test
    void pointsQuestionsAtHowTheUserFeltRatherThanAtMoreFacts() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("그 일을 겪은 사용자의 마음", "사실을 더 캐묻지 않고 그때의 기분을 묻습니다",
                        "'○○ 했을 때 기분이 어땠어요?'", "'어떤 생각이 드셨나요'로 에둘러 묻는 질문은 쓰지 않습니다",
                        "그때의 마음을 아직 한 번도 묻지 않았고", "일어난 일만 듣고 끝내지 않습니다",
                        "같은 것을 다시 묻지 않고 true 로 둡니다", "한 번 물은 것은 한 번으로 끝냅니다");
    }

    @Test
    void keepsTheUsersOwnWordsAndDegree() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("사용자가 쓴 정도 그대로", "'그렇게 실망스러웠던'으로 키우지 않습니다", "말하지 않은 감정을 붙이거나",
                        "'…라고 하셨군요' 금지", "호칭('OO님')은 쓰지 않습니다");
    }

    @Test
    void tellsTheWriterNotToAskOnAQuietTurn() {
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false)))
                .contains("받아주기:", "질문: 이번 턴에는 하지 않습니다");
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.WRAP_UP, false)))
                .contains("여기까지 정리할지는 서버가 묻습니다", "작별 인사를 넣지 않습니다");
    }

    @Test
    void tellsTheWriterToStepBackAndWhy() {
        String prompt = prompt(new TurnStrategy(StrategyType.YIELD, QuestionFocus.OPEN, false,
                "puzzled_or_objecting"));

        assertThat(prompt).contains("물러서기:", "(바바의 말이 이상하거나 지나치다고 되물음)", "직전의 질문이나 해석을 거두고",
                "직전 질문과 같은 방향으로는 다시 묻지 않습니다");
        assertThat(prompt(new TurnStrategy(StrategyType.YIELD, QuestionFocus.OPEN, false, "struggle_to_answer")))
                .contains("(답하려 했지만 말하기 어려워함)");
        assertThat(prompt(new TurnStrategy(StrategyType.YIELD, QuestionFocus.OPEN, false,
                "no_answer_or_topic_change"))).contains("사용자가 꺼낸 얘기를 따라갑니다");
    }

    @Test
    void tellsTheWriterNotToRepeatWhatBabaAlreadySaid() {
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false)))
                .contains("[되풀이하지 않습니다]", "이미 한 말과 뜻이 같은 말을 다시 하지 않습니다",
                        "받아주는 말과 질문 모두에 해당하고", "새로 받을 내용이 없으면 앞의 공감을 다시 쓰지 않고",
                        "쓰기 전에 [대화]에서 바바의 앞선 말을 확인합니다");
    }

    @Test
    void tellsTheWriterWhenTheFeelingIsAlreadyKnown() {
        TurnStrategy asks = new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false);
        assertThat(prompt(asks.withFeelingKnown(true)))
                .contains("그때의 기분은 이미 들었습니다", "기분을 다시 묻지 않고", "방금 답변이 새 이야기가 아니라면");
        assertThat(prompt(asks)).doesNotContain("그때의 기분은 이미 들었습니다");
    }

    @Test
    void asksTheWriterToFlagAQuestionThatRepeatsAnEarlierOne() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("repeatsEarlier", "말이 달라도 묻는 내용이 같으면", "서버가 내보내지 않습니다")
                .doesNotContain("언제 멈출지는");
    }

    @Test
    void tellsTheWriterNotToEchoARequestToBeHeard() {
        String heard = prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false, null, true));
        assertThat(heard).contains("「묻지 않고 듣겠다」는 답은 서버가 따로 합니다", "그 요청을 해석하거나 되풀이하지 말고",
                "요청뿐이면 ack 를 빈 문자열로 둡니다");
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false)))
                .doesNotContain("서버가 따로 합니다");
    }

    @Test
    void asksAsChoicesForAUserWhoKeepsResisting() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, true)))
                .contains("둘 중 고를 수 있는 형태로", "답하지 않아도 괜찮다는 여지");
    }

    @Test
    void reframingAsksForTheUsersOwnGrounds() {
        assertThat(prompt(new TurnStrategy(StrategyType.REFRAME, QuestionFocus.OPEN, false)))
                .contains("재해석:", "근거를 함께 살펴보는 질문", "사용자가 말하게 합니다");
    }

    @Test
    void strategyTurnsUseASystemPromptWithoutTheOldFixedOrderOrNameCalling() {
        assertThat(prompts.system()).contains("감정만 있으면 계기를 한 번 묻고", "\"OO님\"으로 부릅니다");
        assertThat(prompts.strategySystem())
                .doesNotContain("감정만 있으면 계기를", "그 감정이 맞는지 한 번 확인", "\"OO님\"으로 부릅니다")
                .contains("[이번 턴에 할 일]을 따릅니다", "항상 존댓말", "지어내지 않습니다", "안전 신호");
    }

    @ParameterizedTest
    @EnumSource(StrategyType.class)
    void everyCourseHasADirective(StrategyType type) {
        assertThat(prompt(new TurnStrategy(type, QuestionFocus.NONE, false))).contains("[이번 턴의 방침]");
    }
}
