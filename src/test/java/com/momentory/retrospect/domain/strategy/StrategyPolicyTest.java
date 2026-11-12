package com.momentory.retrospect.domain.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 규칙은 굵은 방침만 정한다 — 끝낼지, 물러설지, 질문을 할지, 언제 마무리를 제안할지. 무엇을 물을지는 정하지 않는다.
 */
class StrategyPolicyTest {

    private final StrategyPolicy policy = new StrategyPolicy(0.5, 0.8, 2, 8);

    /** 판단 하나를 조립한다 — 기본은 '계속 · 요청 없음 · 동의', 확신 0.9. 감정 · 사건 · 깊이는 방침에 쓰이지 않는다. */
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
        J reaction(String c) { return set(JudgmentItem.REACTION, c, 0.9); }

        TurnJudgment build() {
            return new TurnJudgment("jev-test", "v3", items, 0, 0);
        }
    }

    /** 한 턴을 끝까지 돈다 — 방침을 고르고, 그 방침이 그대로 나갔다고 보고 신호를 마무리한다. */
    private StrategyPolicy.Decision turn(StrategySignals before, J j) {
        StrategyPolicy.Decision d = policy.decide(j.build(), before, false);
        return new StrategyPolicy.Decision(d.strategy(), policy.settle(d.signals(), d.strategy()));
    }

    private StrategyPolicy.Decision first(J j) {
        StrategyPolicy.Decision d = policy.decide(j.build(), StrategySignals.initial(), true);
        return new StrategyPolicy.Decision(d.strategy(), policy.settle(d.signals(), d.strategy()));
    }

    private static void assertStrategy(StrategyPolicy.Decision d, StrategyType type, QuestionFocus focus) {
        assertThat(d.strategy().type()).isEqualTo(type);
        assertThat(d.strategy().focus()).isEqualTo(focus);
    }

    // ── 기본 ──

    @Test
    void byDefaultItAcknowledgesAndLetsTheWriterAskOneQuestion() {
        assertStrategy(first(new J()), StrategyType.EXPLORE, QuestionFocus.OPEN);
    }

    @Test
    void whatWasSaidOrHowDeepItWasDoesNotChangeTheCourse() {
        // 감정 · 사건 · 체험 깊이는 측정값이다. 무엇을 물을지는 대화 전체를 보는 문장 생성 모델이 정한다.
        for (String depth : new String[] {"stage_1_2", "stage_3", "stage_4", "stage_5_plus", "undeterminable"}) {
            J j = new J().set(JudgmentItem.EXPERIENCING_DEPTH, depth, 0.9)
                    .set(JudgmentItem.EMOTION_EXPRESSION, "clear", 0.9)
                    .set(JudgmentItem.EVENT_INFO, "specific", 0.9);
            assertStrategy(turn(first(new J()).signals(), j), StrategyType.EXPLORE, QuestionFocus.OPEN);
        }
    }

    // ── 끝내려 함 ──

    @Test
    void endRequestClosesBeforeAnythingElse() {
        StrategyPolicy.Decision d = turn(first(new J()).signals(),
                new J().end("end_request", 0.95).reaction("correct_interpretation"));
        assertStrategy(d, StrategyType.CLOSE, QuestionFocus.NONE);
    }

    @Test
    void uncertainEndRequestDoesNotClose() {
        assertThat(first(new J().end("end_request", 0.7)).strategy().type()).isNotEqualTo(StrategyType.CLOSE);
    }

    // ── 받아들이지 않음 → 물러서기 ──

    @ParameterizedTest
    @ValueSource(strings = {"correct_interpretation", "argue", "struggle_to_answer", "puzzled_or_objecting",
            "no_answer_or_topic_change"})
    void yieldsWheneverTheUserDidNotTakeWhatBabaSaidWhateverTheKind(String reaction) {
        StrategyPolicy.Decision d = turn(first(new J()).signals(), new J().reaction(reaction));

        // 고쳐 말했든, 반박했든, 답하기 어려워했든, "뭐?" 라고 했든 대처는 같다.
        assertThat(d.strategy().type()).isEqualTo(StrategyType.YIELD);
        // 어떤 반응이었는지는 문장 생성 모델에 참고로 건넨다. 이어서 물을지는 그 모델이 정한다.
        assertThat(d.strategy().reaction()).isEqualTo(reaction);
        assertThat(d.strategy().focus()).isEqualTo(QuestionFocus.OPEN);
        assertThat(d.signals().yieldStreak()).isEqualTo(1);
    }

    @Test
    void onlyRealResistanceCountsTowardAskingLess() {
        StrategySignals s = first(new J()).signals();
        assertThat(turn(s, new J().reaction("argue")).signals().resistanceCount()).isEqualTo(1);
        assertThat(turn(s, new J().reaction("no_answer_or_topic_change")).signals().resistanceCount()).isEqualTo(1);
        // 못 하겠다와 의아해함은 안 하겠다가 아니다.
        assertThat(turn(s, new J().reaction("struggle_to_answer")).signals().resistanceCount()).isZero();
        assertThat(turn(s, new J().reaction("puzzled_or_objecting")).signals().resistanceCount()).isZero();
        assertThat(turn(s, new J().reaction("correct_interpretation")).signals().resistanceCount()).isZero();
    }

    @Test
    void doesNotReadAReactionWhenBabaLeftItToTheUser() {
        // 첫 질문 뒤나 「더 나누고 싶은 이야기를 들려주세요」 뒤에 다른 얘기를 꺼내는 것은 받아들이지 않은 것이 아니다.
        StrategyPolicy.Decision d = policy.decide(new J().reaction("no_answer_or_topic_change").build(),
                first(new J()).signals(), true);

        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OPEN);
        assertThat(d.signals().resistanceCount()).isZero();
        assertThat(d.signals().yieldStreak()).isZero();
    }

    @Test
    void anAcceptedAnswerBreaksTheStreak() {
        StrategyPolicy.Decision yielded = turn(first(new J()).signals(), new J().reaction("struggle_to_answer"));
        assertThat(turn(yielded.signals(), new J()).signals().yieldStreak()).isZero();
    }

    @Test
    void lowConfidenceSignalsAreIgnored() {
        StrategyPolicy.Decision d = turn(first(new J()).signals(),
                new J().set(JudgmentItem.REACTION, "argue", 0.4).set(JudgmentItem.REQUESTED_MODE, "listen", 0.3));

        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OPEN);
        assertThat(d.signals().resistanceCount()).isZero();
        assertThat(d.signals().explicitMode()).isNull();
    }

    // ── 들어 달라 함 · 다른 관점을 원함 ──

    @Test
    void listenRequestStopsQuestionsUntilTheUserAsksOtherwise() {
        StrategyPolicy.Decision listen = turn(first(new J()).signals(), new J().requested("listen"));
        assertStrategy(listen, StrategyType.REFLECT, QuestionFocus.NONE);
        // 다음 답변에 별다른 요청이 없어도 가장 최근의 명시적 의사를 따른다.
        StrategyPolicy.Decision still = turn(listen.signals(), new J());
        assertStrategy(still, StrategyType.REFLECT, QuestionFocus.NONE);
        // 함께 알아보자고 하면 다시 묻는다.
        assertStrategy(turn(still.signals(), new J().requested("explore")), StrategyType.EXPLORE,
                QuestionFocus.OPEN);
    }

    @Test
    void refusedQuestionIsTreatedLikeAListenRequest() {
        StrategyPolicy.Decision d = turn(first(new J()).signals(), new J().reaction("refuse_question"));
        assertStrategy(d, StrategyType.REFLECT, QuestionFocus.NONE);
        assertThat(d.signals().explicitMode()).isEqualTo(StrategySignals.LISTEN);
    }

    @Test
    void aListeningUserIsNotAskedEvenWhenBabaYields() {
        StrategyPolicy.Decision listening = turn(first(new J()).signals(), new J().requested("listen"));
        assertStrategy(turn(listening.signals(), new J().reaction("correct_interpretation")), StrategyType.YIELD,
                QuestionFocus.NONE);
    }

    @Test
    void otherViewRequestLeadsToReframingForTheRestOfTheTalk() {
        StrategyPolicy.Decision d = first(new J().requested("other_view"));
        assertStrategy(d, StrategyType.REFRAME, QuestionFocus.OPEN);
        assertThat(turn(d.signals(), new J()).strategy().type()).isEqualTo(StrategyType.REFRAME);
    }

    // ── 더 물을 것이 없을 때(문장 생성 모델이 알린다) ──

    @Test
    void asksForAnotherMemoryWhenTheFirstStoryIsDone() {
        StrategyPolicy.Decision d = policy.decide(new J().build(), first(new J()).signals(), false);

        TurnStrategy done = policy.whenNothingMoreToAsk(d.strategy(), d.signals(), false);

        assertThat(done.focus()).isEqualTo(QuestionFocus.OTHER_TOPIC);
        assertThat(done.type()).isEqualTo(StrategyType.EXPLORE);
    }

    @Test
    void doesNotAskForAnotherMemoryTwice() {
        StrategyPolicy.Decision d = policy.decide(new J().build(), first(new J()).signals(), false);

        // 다른 기억을 이미 물었는데 새 이야기가 나오지 않았다 — 같은 질문 대신 마무리를 제안한다.
        assertThat(policy.whenNothingMoreToAsk(d.strategy(), d.signals(), true).focus())
                .isEqualTo(QuestionFocus.WRAP_UP);
    }

    @Test
    void proposesWrapUpWhenTheSecondStoryIsDoneAndOnlyOnce() {
        StrategySignals second = policy.movedToNewTopic(first(new J()).signals());
        assertThat(second.topicNumber()).isEqualTo(2);
        StrategyPolicy.Decision d = policy.decide(new J().build(), second, false);

        TurnStrategy proposal = policy.whenNothingMoreToAsk(d.strategy(), d.signals(), false);
        assertThat(proposal.focus()).isEqualTo(QuestionFocus.WRAP_UP);

        // 더 이야기하겠다고 한 뒤에 또 물을 것이 없으면 다시 제안하지 않고 받아주기만 한다.
        StrategySignals after = policy.settle(d.signals(), proposal);
        assertThat(after.wrapUpProposed()).isTrue();
        StrategyPolicy.Decision again = policy.decide(new J().build(), after, false);
        assertThat(policy.whenNothingMoreToAsk(again.strategy(), again.signals(), false).focus())
                .isEqualTo(QuestionFocus.NONE);
    }

    @Test
    void aNewStoryStartsFreshAndStoriesStopAtTwo() {
        StrategySignals proposed = policy.settle(first(new J()).signals(),
                new TurnStrategy(StrategyType.REFLECT, QuestionFocus.WRAP_UP, false));

        StrategySignals second = policy.movedToNewTopic(proposed);
        assertThat(second.topicNumber()).isEqualTo(2);
        assertThat(second.wrapUpProposed()).isFalse();
        // 사건은 둘까지 뽑는다 — 셋째 이야기는 세지 않는다.
        assertThat(policy.movedToNewTopic(second)).isEqualTo(second);
    }

    // ── 분량 ──

    @Test
    void proposesWrapUpOnceTheQuestionCapIsReached() {
        StrategyPolicy capped = new StrategyPolicy(0.5, 0.8, 2, 2);
        StrategyPolicy.Decision d = capped.decide(new J().build(), StrategySignals.initial(), true);
        StrategySignals s = capped.settle(d.signals(), d.strategy());
        d = capped.decide(new J().build(), s, false);
        s = capped.settle(d.signals(), d.strategy());
        assertThat(s.questionsAsked()).isEqualTo(2);

        // 충분히 물었다 — 말없이 받아주기만 하다 끝나지 않게, 여기까지 정리할지 묻는다.
        d = capped.decide(new J().build(), s, false);
        assertStrategy(d, StrategyType.REFLECT, QuestionFocus.WRAP_UP);
        s = capped.settle(d.signals(), d.strategy());
        assertThat(s.questionsAsked()).isEqualTo(2);

        // 더 이야기하겠다고 하면 묻지 않고 받아주기만 한다.
        assertStrategy(capped.decide(new J().build(), s, false), StrategyType.REFLECT, QuestionFocus.NONE);
    }

    @Test
    void proposesWrapUpWhenTheUserKeepsNotTakingWhatBabaSays() {
        StrategyPolicy.Decision d = turn(first(new J()).signals(), new J().reaction("puzzled_or_objecting"));
        assertThat(d.strategy().focus()).isEqualTo(QuestionFocus.OPEN);

        d = turn(d.signals(), new J().reaction("struggle_to_answer"));

        assertStrategy(d, StrategyType.YIELD, QuestionFocus.WRAP_UP);
    }

    @Test
    void asksRarelyAndAsChoicesOnceResistanceRepeats() {
        StrategyPolicy.Decision d = turn(first(new J()).signals(), new J().reaction("argue"));
        d = turn(d.signals(), new J());                                  // 받아들임 — 연달은 거절은 끊긴다
        d = turn(d.signals(), new J().reaction("no_answer_or_topic_change"));   // 저항 2회째
        assertThat(d.signals().resistanceCount()).isEqualTo(2);
        // 방금 물었으니 이번 턴은 쉰다.
        assertThat(d.strategy().asks()).isFalse();

        d = turn(d.signals(), new J());
        assertThat(d.strategy().asks()).isFalse();
        d = turn(d.signals(), new J());                                  // 두 턴 쉬었으니 한 번, 선택지 형태로
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OPEN);
        assertThat(d.strategy().asChoices()).isTrue();
    }

    // ── 판단이 없을 때 ──

    @Test
    void withoutAJudgmentItStillAcknowledgesAndAsks() {
        StrategyPolicy.Decision d = policy.decideWithoutJudgment(StrategySignals.initial(), false);
        assertStrategy(d, StrategyType.EXPLORE, QuestionFocus.OPEN);
        assertThat(d.signals()).isEqualTo(StrategySignals.initial());
    }

    @Test
    void withoutAJudgmentAClearStopPhraseStillCloses() {
        assertStrategy(policy.decideWithoutJudgment(StrategySignals.initial(), true), StrategyType.CLOSE,
                QuestionFocus.NONE);
    }
}
