package com.momentory.retrospect.domain.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

class StrategyPolicyTest {

    private final StrategyPolicy policy = new StrategyPolicy(0.5, 0.8, 2);

    /** 판단 하나를 조립한다 — 기본은 '계속 · 요청 없음 · 감정 없음 · 사건 없음 · 동의 · 1~2단계', 확신 0.9. */
    private static final class J {
        private final Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);

        J() {
            set(JudgmentItem.END_INTENT, "continue", 0.9);
            set(JudgmentItem.REQUESTED_MODE, "none", 0.9);
            set(JudgmentItem.EMOTION_EXPRESSION, "none", 0.9);
            set(JudgmentItem.EVENT_INFO, "none", 0.9);
            set(JudgmentItem.REACTION, "agree_or_elaborate", 0.9);
            set(JudgmentItem.EXPERIENCING_DEPTH, "stage_1_2", 0.9);
        }

        J set(JudgmentItem item, String choice, double confidence) {
            items.put(item, new ItemJudgment(choice, confidence, Map.of(choice, confidence)));
            return this;
        }

        J end(String c, double conf) { return set(JudgmentItem.END_INTENT, c, conf); }
        J requested(String c) { return set(JudgmentItem.REQUESTED_MODE, c, 0.9); }
        J emotion(String c) { return set(JudgmentItem.EMOTION_EXPRESSION, c, 0.9); }
        J event(String c) { return set(JudgmentItem.EVENT_INFO, c, 0.9); }
        J reaction(String c) { return set(JudgmentItem.REACTION, c, 0.9); }
        J depth(String c) { return set(JudgmentItem.EXPERIENCING_DEPTH, c, 0.9); }

        /** 감정과 사건이 다 나온 답변. */
        J told(String depth) { return emotion("clear").event("specific").depth(depth); }

        TurnJudgment build() {
            return new TurnJudgment("jev-test", "v1", items, 0, 0);
        }
    }

    private StrategyPolicy.Decision first(J j) {
        return policy.decide(j.build(), StrategySignals.initial(), true);
    }

    private StrategyPolicy.Decision next(StrategyPolicy.Decision before, J j) {
        return policy.decide(j.build(), before.signals(), false);
    }

    private static void assertStrategy(StrategyPolicy.Decision d, StrategyType type, QuestionFocus focus) {
        assertThat(d.strategy().type()).isEqualTo(type);
        assertThat(d.strategy().focus()).isEqualTo(focus);
    }

    // ── 1. 먼저 적용하는 제약 ──

    @Test
    void endRequestClosesBeforeAnythingElse() {
        // "화난 게 아니라 서운한 거야. 오늘은 여기까지." — 해석 수정이면서 종료 요청이면 종료가 먼저다.
        StrategyPolicy.Decision d = next(first(new J().told("stage_3")),
                new J().end("end_request", 0.95).reaction("correct_interpretation").told("stage_3"));
        assertStrategy(d, StrategyType.CLOSE, QuestionFocus.NONE);
    }

    @Test
    void uncertainEndRequestDoesNotClose() {
        StrategyPolicy.Decision d = first(new J().end("end_request", 0.7));
        assertThat(d.strategy().type()).isNotEqualTo(StrategyType.CLOSE);
    }

    @Test
    void correctedInterpretationIsReflectedBackAndTheSameTurnKeepsAsking() {
        // "아 분함이야. 잘못 말했네" — 고친 표현을 받고, 멈추지 않고 한 단계 깊게 묻는다.
        StrategyPolicy.Decision d = next(first(new J().told("stage_3")),
                new J().told("stage_3").reaction("correct_interpretation"));
        assertStrategy(d, StrategyType.CORRECT, QuestionFocus.FEELING);
    }

    @Test
    void aCorrectionNeverMovesToAnotherMemory() {
        StrategyPolicy.Decision d = first(new J().event("specific"));
        d = next(d, new J().event("specific"));
        // 정체가 두 번째인 턴이지만 방금 고쳐 말한 소재를 떠나지 않는다.
        d = next(d, new J().event("specific").reaction("correct_interpretation"));
        assertStrategy(d, StrategyType.CORRECT, QuestionFocus.SELF);
    }

    @Test
    void listenRequestStopsQuestionsUntilTheUserAsksOtherwise() {
        StrategyPolicy.Decision listen = next(first(new J()), new J().requested("listen"));
        assertStrategy(listen, StrategyType.REFLECT, QuestionFocus.NONE);
        // 다음 답변에 별다른 요청이 없어도 가장 최근의 명시적 의사를 따른다.
        StrategyPolicy.Decision still = next(listen, new J());
        assertStrategy(still, StrategyType.REFLECT, QuestionFocus.NONE);
        // 탐색을 요청하면 다시 묻는다.
        StrategyPolicy.Decision explore = next(still, new J().requested("explore").event("specific"));
        assertThat(explore.strategy().type()).isEqualTo(StrategyType.EXPLORE);
        assertThat(explore.strategy().asks()).isTrue();
    }

    @Test
    void refusedQuestionIsTreatedLikeAListenRequest() {
        StrategyPolicy.Decision d = next(first(new J()), new J().reaction("refuse_question"));
        assertStrategy(d, StrategyType.REFLECT, QuestionFocus.NONE);
        assertThat(d.signals().explicitMode()).isEqualTo(StrategySignals.LISTEN);
    }

    // ── 2. 유형 + 4. 질문 깊이 ──

    @Test
    void asksWhatHappenedWhenTheEventIsMissing() {
        assertStrategy(first(new J().emotion("clear").depth("stage_3")), StrategyType.EXPLORE, QuestionFocus.EVENT);
        assertStrategy(first(new J().event("partial")), StrategyType.EXPLORE, QuestionFocus.EVENT);
    }

    @Test
    void asksAboutTheEventOnlyOncePerTopic() {
        StrategyPolicy.Decision asked = first(new J().event("partial"));
        assertStrategy(asked, StrategyType.EXPLORE, QuestionFocus.EVENT);
        // 여전히 일부만 들었어도 같은 것을 되묻지 않고 자기 쪽으로 넘어간다.
        assertStrategy(next(asked, new J().event("partial")), StrategyType.EXPLORE, QuestionFocus.SELF);
    }

    @Test
    void movesFromTheEventToTheSelfWhenOnlyFactsWereTold() {
        // "오늘 팀장한테 또 한 소리 들었어." — 사건은 나왔고 감정은 없다(체험 1~2단계).
        assertStrategy(first(new J().event("specific")), StrategyType.EXPLORE, QuestionFocus.SELF);
    }

    @Test
    void asksOneStepDeeperEveryTurnEvenWhenEmotionAndEventAreBothOut() {
        // "발표 망쳐서 허무해." — 받아주기만 하고 끝내면 대화가 멈춘다. 인정 뒤에 한 단계 깊게 묻는다.
        StrategyPolicy.Decision first = first(new J().told("stage_3"));
        assertStrategy(first, StrategyType.EXPLORE, QuestionFocus.FEELING);
        assertStrategy(next(first, new J().told("stage_4")), StrategyType.EXPLORE, QuestionFocus.MEANING);
    }

    @Test
    void doesNotAskAfterACorrectionWhenTheUserOnlyWantsToBeHeard() {
        StrategyPolicy.Decision listening = next(first(new J().told("stage_3")), new J().requested("listen"));
        assertStrategy(listening, StrategyType.REFLECT, QuestionFocus.NONE);
        StrategyPolicy.Decision corrected = next(listening, new J().reaction("correct_interpretation"));
        assertStrategy(corrected, StrategyType.CORRECT, QuestionFocus.NONE);

        assertStrategy(next(corrected, new J().told("stage_3")), StrategyType.REFLECT, QuestionFocus.NONE);
    }

    @Test
    void followsUpOnWhatTheUserJustNoticedAboutThemselves() {
        // "내가 항상 후반부에 못하거든" — 받아주기만 하지 않고 방금 짚은 것을 한 번 더 묻는다.
        assertStrategy(next(first(new J().told("stage_3")), new J().told("stage_5_plus")),
                StrategyType.EXPLORE, QuestionFocus.FOLLOW_UP);
    }

    @Test
    void aShortAgreementIsMarkedBriefSoTheReplyGoesStraightToTheNextQuestion() {
        StrategyPolicy.Decision asked = first(new J().told("stage_3"));
        assertThat(asked.strategy().brief()).isFalse();

        // "맞아" — 체험 깊이를 확신 있게 '판단 불가'로 본 답변.
        StrategyPolicy.Decision agreed = next(asked, new J().depth("undeterminable"));
        assertThat(agreed.strategy().brief()).isTrue();
        assertStrategy(agreed, StrategyType.EXPLORE, QuestionFocus.FEELING);
        // 확신이 낮으면 짧은 동의로 단정하지 않는다.
        assertThat(next(asked, new J().set(JudgmentItem.EXPERIENCING_DEPTH, "undeterminable", 0.4))
                .strategy().brief()).isFalse();
    }

    @Test
    void otherViewRequestLeadsToReframing() {
        StrategyPolicy.Decision d = first(new J().told("stage_3").requested("other_view"));
        assertStrategy(d, StrategyType.REFRAME, QuestionFocus.EVIDENCE);
        // 이번 대화에서는 그 요청이 이어진다.
        assertThat(next(d, new J().told("stage_3")).strategy().type()).isEqualTo(StrategyType.REFRAME);
    }

    @Test
    void adviceRequestHasNoStrategyOfItsOwn() {
        assertStrategy(first(new J().told("stage_3").requested("advice")), StrategyType.EXPLORE,
                QuestionFocus.FEELING);
    }

    // ── 소재 전환 ──

    @Test
    void staysOnTheTopicWhileDepthKeepsRising() {
        StrategyPolicy.Decision d = first(new J().event("specific"));                 // 2단계 → SELF
        d = next(d, new J().told("stage_3"));                                         // 올랐다 → 감정 쪽으로
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.FEELING);
        d = next(d, new J().told("stage_4"));                                         // 올랐다 → 그 마음의 이유로
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.MEANING);
        d = next(d, new J().told("stage_5_plus"));                                    // 올랐다 → 방금 짚은 것을 한 번 더
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.FOLLOW_UP);
        assertThat(d.signals().topicStall()).isZero();
        assertThat(d.signals().topicDepth()).isEqualTo(5);
    }

    @Test
    void movesToAnotherMemoryAfterTwoQuestionsWithoutGettingDeeper() {
        StrategyPolicy.Decision d = first(new J().event("specific"));                 // SELF 질문
        d = next(d, new J().event("specific"));                                       // 그대로 2단계 — 정체 1
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.SELF);
        d = next(d, new J().event("specific"));                                       // 정체 2 → 다른 기억
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC);
        // 넘어가면 소재에 묶인 신호는 비운다.
        assertThat(d.signals().topicDepth()).isZero();
        assertThat(d.signals().topicStall()).isZero();
        assertThat(d.signals().eventOut()).isFalse();
    }

    @Test
    void movesOnAfterTheDeepestStageWasFollowedUpOnce() {
        StrategyPolicy.Decision d = first(new J().told("stage_5_plus"));
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.FOLLOW_UP);
        assertStrategy(next(d, new J().told("stage_5_plus")), StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC);
    }

    @Test
    void leavesTheTopicWhenTheUserFindsTheQuestionHardToAnswer() {
        // "그냥 전부." — 답하려 했지만 가려 말하기 어려워했다. 같은 것을 다시 묻지 않고 다른 기억으로 넘어간다.
        StrategyPolicy.Decision asked = first(new J().told("stage_3"));
        StrategyPolicy.Decision d = next(asked, new J().told("stage_3").reaction("struggle_to_answer"));

        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC);
        // 못 하겠다는 안 하겠다가 아니다 — 저항으로 세지 않고, 질문 빈도도 줄이지 않는다.
        assertThat(d.signals().resistanceCount()).isZero();
        assertThat(d.strategy().asChoices()).isFalse();
        assertThat(d.signals().topicDepth()).isZero();
    }

    @Test
    void aStruggledShortAnswerIsStillAcknowledgedBeforeMovingOn() {
        // "몰라" — 짧아도 받아주는 말을 건너뛰지 않는다.
        StrategyPolicy.Decision d = next(first(new J().told("stage_3")),
                new J().reaction("struggle_to_answer").depth("undeterminable"));

        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC);
        assertThat(d.strategy().brief()).isFalse();
    }

    @Test
    void doesNotAskEvenAfterAStruggleWhenTheUserOnlyWantsToBeHeard() {
        StrategyPolicy.Decision listening = next(first(new J().told("stage_3")), new J().requested("listen"));

        assertStrategy(next(listening, new J().reaction("struggle_to_answer")), StrategyType.REFLECT,
                QuestionFocus.NONE);
    }

    @Test
    void asksHowItWasForTheUserBeforeAnyFeelingWasNamed() {
        // "오늘 시험 쳤는데 망한 거 같아" — 사건은 나왔고 감정은 말하지 않았다. 깊이가 3단계로 판단돼도
        // 받을 감정이 없으니 그 감정을 묻지 않는다.
        assertStrategy(first(new J().event("specific").depth("stage_3")), StrategyType.EXPLORE,
                QuestionFocus.SELF);
        assertStrategy(first(new J().event("specific").emotion("vague").depth("stage_4")), StrategyType.EXPLORE,
                QuestionFocus.SELF);
    }

    // ── 3. 질문 빈도 ──

    @Test
    void dropsTheQuestionOnTheTurnTheUserResisted() {
        StrategyPolicy.Decision d = next(first(new J().event("specific")),
                new J().event("specific").reaction("no_answer_or_topic_change"));
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.NONE);
        assertThat(d.signals().resistanceCount()).isEqualTo(1);
    }

    @Test
    void asksRarelyAndAsChoicesOnceResistanceRepeats() {
        StrategyPolicy.Decision d = first(new J().event("specific"));
        d = next(d, new J().event("specific").reaction("argue"));                       // 저항 1 — 질문 없음
        d = next(d, new J().event("specific").reaction("no_answer_or_topic_change"));   // 저항 2 — 질문 없음
        assertThat(d.signals().resistanceCount()).isEqualTo(2);
        assertThat(d.strategy().asks()).isFalse();
        d = next(d, new J().event("specific"));                                         // 두 턴 쉬었으니 한 번 묻는다
        assertThat(d.strategy().asks()).isTrue();
        assertThat(d.strategy().asChoices()).isTrue();
        d = next(d, new J().event("specific"));                                         // 바로 다음 턴은 쉰다
        assertThat(d.strategy().asks()).isFalse();
    }

    @Test
    void firstAnswerIsNeverCountedAsAReaction() {
        StrategyPolicy.Decision d = first(new J().event("specific").reaction("no_answer_or_topic_change"));
        assertThat(d.signals().resistanceCount()).isZero();
        assertThat(d.strategy().asks()).isTrue();
    }

    // ── 판단할 수 없을 때 ──

    @Test
    void lowConfidenceItemsAreSkippedAndNotCounted() {
        StrategyPolicy.Decision d = next(first(new J().told("stage_3")),
                new J().set(JudgmentItem.REACTION, "argue", 0.4)
                        .set(JudgmentItem.REQUESTED_MODE, "listen", 0.3)
                        .set(JudgmentItem.EXPERIENCING_DEPTH, "stage_5_plus", 0.45));
        assertThat(d.signals().resistanceCount()).isZero();
        assertThat(d.signals().explicitMode()).isNull();
        assertThat(d.signals().topicDepth()).isEqualTo(3);
        // 앞에서 나온 감정·사건과 깊이는 유지하고, 그 깊이에서 한 단계 깊게 묻는다.
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.FEELING);
    }

    @Test
    void anUndeterminableShortAnswerKeepsEarlierSignals() {
        StrategyPolicy.Decision before = first(new J().told("stage_4"));
        StrategyPolicy.Decision d = next(before, new J().reaction("undeterminable").depth("undeterminable"));
        assertThat(d.signals().topicDepth()).isEqualTo(4);
        assertThat(d.signals().emotionOut()).isTrue();
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.MEANING);
    }
}
