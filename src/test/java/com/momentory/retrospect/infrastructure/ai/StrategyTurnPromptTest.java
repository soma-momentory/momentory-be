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
    void tellsTheModelNotToAskOnAReflectionTurn() {
        String prompt = prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false));

        assertThat(prompt).contains("공감·반영:", "이번 턴에는 질문하지 않습니다", "발표 망쳐서 허무해.");
        // 기존 고정 순서의 지시는 섞이지 않는다.
        assertThat(prompt).doesNotContain("[판단 순서와 질문 목적]", "다른 기억에 남는 일을 물으세요");
    }

    @Test
    void aimsTheQuestionOneStepDeeper() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.FEELING, false)))
                .contains("탐색:", "어떤 점이 가장 그렇게 느껴지게 했는지", "사용자가 방금 쓴 구체적인 말을 받아서")
                .doesNotContain("둘 중 고를 수 있는", "짧게 동의만");
        assertThat(prompt(new TurnStrategy(StrategyType.REFRAME, QuestionFocus.EVIDENCE, false)))
                .contains("재해석:", "사용자가 말하게 하세요", "근거나 부분을 묻는 질문");
    }

    @Test
    void asksAsChoicesForAUserWhoKeepsResisting() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.SELF, true)))
                .contains("둘 중 고를 수 있는 형태로", "답하지 않아도 괜찮다는 여지");
    }

    @Test
    void leavesTheNextTopicQuestionToTheServer() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC, false)))
                .contains("다음 질문은 서버가 붙입니다", "더 캐묻거나 이유를 짐작하지 않습니다");
    }

    @Test
    void followsUpOnWhatTheUserNoticedInTheirOwnWords() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.FOLLOW_UP, false)))
                .contains("방금 스스로 짚은 것", "새로운 해석을 덧붙이지 않습니다");
    }

    @Test
    void skipsTheEmpathyAfterAShortAgreement() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.FEELING, false, true)))
                .contains("짧게 동의만 했습니다", "empathy는 빈 문자열", "동의를 평가하거나 고마워하지 않습니다");
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false, true)))
                .contains("짧게 동의만 했습니다", "편하게 이어 말해도 된다는 뜻만");
    }

    @Test
    void strategyTurnsUseASystemPromptWithoutTheOldFixedOrder() {
        assertThat(prompts.system()).contains("감정만 있으면 계기를 한 번 묻고");
        assertThat(prompts.strategySystem())
                .doesNotContain("감정만 있으면 계기를", "그 감정이 맞는지 한 번 확인")
                .contains("[이번 턴에 할 일]을 따릅니다", "항상 존댓말", "지어내지 않습니다", "안전 신호");
    }

    @ParameterizedTest
    @EnumSource(QuestionFocus.class)
    void everyFocusHasADirective(QuestionFocus focus) {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, focus, false))).contains("- 질문: ");
    }
}
