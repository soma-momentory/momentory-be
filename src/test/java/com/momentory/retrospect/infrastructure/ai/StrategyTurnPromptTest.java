package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
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

        assertThat(prompt).contains("탐색:", "질문: 해도 됩니다.", "한 걸음만 더 안쪽으로", "말한 그 생각을", "발표 망쳐서 허무해.",
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
                .contains("그 일을 겪은 사용자의 기분과 생각", "사실을 더 캐묻지 않고 그때의 기분을 묻습니다",
                        "'○○ 했을 때 기분이 어땠어요?'", "기분을 물을 차례에 '어떤 생각이 드셨나요'로 에둘러 묻지 않습니다",
                        "그때의 기분을 아직 한 번도 묻지 않았고", "일어난 일만 듣고 끝내지 않습니다",
                        "같은 것을 다시 묻지 않고 true 로 둡니다", "한 번 물은 것은 한 번으로 끝냅니다");
    }

    @Test
    void asksAboutTheUsersThoughtOfThemselvesAfterTheFeelingInsteadOfTheirHeart() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("기분을 말했으면 그때 자기 자신에 대해서는 어떤 생각이 들었는지를",
                        "'그때 스스로에 대해서는 어떤 생각이 들었어요?'",
                        "asksFeeling: question 이 그때의 기분을 물으면 true")
                .doesNotContain("어떤 마음이 들었는지", "자기 마음을 스스로 짚었으면");
    }

    @Test
    void keepsAskingOnceMoreAfterTheUserNamesAThoughtAboutThemselves() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("사용자가 방금 그 생각을 처음 말했으면 반드시 false",
                        "이미 이어 물었고 사용자가 답했으면 true", "말한 그 생각을 사용자가 쓴 말로 받아 한 번 더 묻습니다",
                        "이어 한 걸음 더 묻는 것은 다시 묻는 것이 아닙니다", "사용자가 답한 생각을 이어 더 묻는 질문은 false");
    }

    @Test
    void tellsTheWriterWhatToAskFromTheServersCount() {
        TurnStrategy known = new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false).withFeelingKnown(true);

        assertThat(prompt(known.withAskedAfterFeeling(0))).contains("이번에는 자기 자신에 대한 생각을 묻습니다",
                "사건이나 다른 사람의 말에 대한 생각이 아니라");
        assertThat(prompt(known.withAskedAfterFeeling(1))).contains("방금 말한 자기 자신에 대한 생각을 그 말로 받아 한 번 더 묻습니다",
                "사실만 더 말했으면 앞의 질문을 다시 묻지 않습니다", "앞의 질문에 상황만 덧붙여 다시 물은 것도 true");
        assertThat(prompt(known.withAskedAfterFeeling(2))).contains("이 이야기에서 물을 것은 다 물었습니다");
        assertThat(prompt(known.withAskedAfterFeeling(2).withAfterOtherMemory(true)))
                .doesNotContain("이 이야기에서 물을 것은 다 물었습니다");
    }

    @Test
    void tellsTheWriterHowToJudgeNewContentWhileHearingOut() {
        TurnStrategy quiet = new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false);

        assertThat(prompt(quiet.withHearingOut(true))).contains("더 얘기하고 싶다고 한 이야기를 듣고 있습니다",
                "nothingMoreToAsk 와 ackRepeatsEarlier 를 true 로 둡니다");
        assertThat(prompt(quiet)).doesNotContain("더 얘기하고 싶다고 한 이야기를 듣고 있습니다");
    }

    @Test
    void keepsTheWordHeartOutOfQuestions() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("질문에는 '마음'이라는 말을 쓰지 않습니다", "'그때 기분이 어땠어요?'로 묻습니다");
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
        assertThat(heard).contains("공감해 달라고 했습니다", "그 요청을 해석하거나", "약속하는 말도 쓰지 않습니다",
                "앞에서 한 이야기를 구체적으로 짚어 공감합니다", "그 상황에 맞는 감정을 짚어 줘도",
                "공감이 모자랐다고 사과하지", "ack 를 빈 문자열로 둡니다");
        assertThat(prompt(new TurnStrategy(StrategyType.REFLECT, QuestionFocus.NONE, false)))
                .doesNotContain("약속하는 말도 쓰지 않습니다");
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
    void asksTheWriterToTakeTheUsersSideWhenTheyAskIfTheyAreWrong() {
        assertThat(prompt(new TurnStrategy(StrategyType.REFRAME, QuestionFocus.OPEN, false)))
                .contains("[자기 편을 들어 달라는 물음]", "'내가 이상해?'", "사용자 편에서 분명하게",
                        "상대를 나쁘게 말하거나 탓하지는 않습니다", "이 턴에는 질문하지 않습니다",
                        "재해석이어도 이것이 먼저입니다", "askedForSide: 위 [자기 편을 들어 달라는 물음]대로",
                        "예외: 방금 답변이 자기 편을", "물음을 되받기만 하는 것은 답이 아닙니다");
    }

    @Test
    void tellsTheWriterToAgreeAndNotAskWhenTheUserWantsToTalkMore() {
        assertThat(prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false)))
                .contains("[더 얘기하고 싶다는 말]", "어떤 일을 더 얘기하고 싶다고 분명히 말했으면 true",
                        "이어 말하기만 한 것은 false", "먼저 알겠다고 답합니다", "'네, 그 이야기 더 들을게요.'",
                        "내용을 짚어 공감하는 문장을 꼭 붙입니다", "ackRepeatsEarlier", "새로 받을 내용이 없으면 true",
                        "되풀이하거나 강조하기만 하고 새로운 사실·기분·생각이");
    }

    @Test
    void tellsTheWriterToGoBackToThePreviousStoryOnlyRightAfterAskingForAnother() {
        TurnStrategy asks = new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false);
        assertThat(prompt(asks.withAfterOtherMemory(true)))
                .contains("바바가 방금 다른 기억에 남는 일을 물었습니다", "다른 일을 말했으면 newTopic 을 true 로",
                        "앞 이야기로 돌아가 방금 새로 말한 것을 받고",
                        "nothingMoreToAsk 는 false 입니다");
        assertThat(prompt(asks)).doesNotContain("바바가 방금 다른 기억에 남는 일을 물었습니다");
    }

    @Test
    void strategyTurnsUseASystemPromptWithoutTheOldFixedOrderOrNameCalling() {
        assertThat(prompts.system()).contains("감정만 있으면 계기를 한 번 묻고", "\"OO님\"으로 부릅니다");
        assertThat(prompts.strategySystem())
                .doesNotContain("감정만 있으면 계기를", "그 감정이 맞는지 한 번 확인", "\"OO님\"으로 부릅니다")
                .contains("[이번 턴에 할 일]을 따릅니다", "항상 존댓말", "지어내지 않습니다", "안전 신호");
    }

    @Test
    void judgesCrisisOnlyFromTheLatestAnswerAndTheUsersOwnRisk() {
        String prompt = prompt(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false));

        assertThat(prompt).contains("- safetyLevel: none|caution|risk|imminent. [방금 사용자 답변]만 보고 매깁니다.",
                "방금 답변에 새 위험 신호가 없으면 none", "사용자 본인이 스스로를 해치거나",
                "다른 사람의 죽음·자살 소식을 전하거나 그 사람에 대한 생각·평가를 말한 것", "자기는 괜찮다는 해명은 위기가");
        // 기준이 목록의 다른 항목처럼 줄 머리에서 시작한다(텍스트 블록 들여쓰기가 섞이지 않는다).
        assertThat(prompt).containsPattern("(?m)^- safetyLevel: ").doesNotContain("%s");
    }

    @Test
    void replacesTheCrisisGuidanceInTheHistoryWithAShortMark() {
        SafetyPolicy safety = new SafetyPolicy();
        RetrospectState state = new RetrospectState("s1");
        state.addAssistantMessage("그때 스스로에 대해서는 어떤 생각이 들었어요?");
        state.addUserMessage("아는 사람이 자살했다는데 이해가 안 돼.");
        state.addAssistantMessage(safety.guidanceFor(SafetyLevel.IMMINENT).render());
        state.addAssistantMessage("그때 스스로에 대해서는 어떤 생각이 들었어요?");
        state.addUserMessage("아니 난 괜찮았다니까");

        String prompt = prompts.strategyTurnPrompt(state, "아니 난 괜찮았다니까",
                new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false));

        // 사용자의 말은 그대로 두고, 바바의 안내 전문만 짧은 표시로 바꾼다.
        assertThat(prompt).contains("나: 아는 사람이 자살했다는데 이해가 안 돼.", "바바: " + PromptFactory.GUIDANCE_MARK)
                .doesNotContain("지금 느끼는 고통이", "자살예방 상담전화 109");
        assertThat(prompts.diaryTurnPrompt(state, "아니 난 괜찮았다니까"))
                .contains("[방금 사용자 답변]만 보고 매깁니다", PromptFactory.GUIDANCE_MARK)
                .doesNotContain("지금 느끼는 고통이");
    }

    @ParameterizedTest
    @EnumSource(StrategyType.class)
    void everyCourseHasADirective(StrategyType type) {
        assertThat(prompt(new TurnStrategy(type, QuestionFocus.NONE, false))).contains("[이번 턴의 방침]");
    }
}
