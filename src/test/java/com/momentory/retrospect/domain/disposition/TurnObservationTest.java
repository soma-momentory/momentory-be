package com.momentory.retrospect.domain.disposition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

class TurnObservationTest {

    private static final double MIN = 0.5;

    /** 판단에 쓰는 두 항목만 바꿔 쓴다. */
    private static TurnJudgment judged(String requested, String emotion, double confidence) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        for (JudgmentItem item : JudgmentItem.values()) {
            String choice = switch (item) {
                case REQUESTED_MODE -> requested;
                case EMOTION_EXPRESSION -> emotion;
                default -> item.choices().getFirst();
            };
            items.put(item, new ItemJudgment(choice, confidence, Map.of(choice, confidence)));
        }
        return new TurnJudgment("jev-test", "v3", items, 0, 0);
    }

    /** @param reaction 규칙이 실제로 본 반응 — 보지 않았으면 null */
    private static TurnJudgmentLog log(TurnJudgment judgment, String reaction, boolean askedFeeling) {
        return TurnJudgmentLog.of("u1", 1, judgment)
                .withStrategy(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false, reaction), "v8", true)
                .withAskedFeeling(askedFeeling);
    }

    @Test
    void countsResistanceOnlyFromTheReactionTheRulesActuallyUsed() {
        assertThat(TurnObservation.of(log(judged("none", "none", 0.9), "argue", false), false, MIN).resisted())
                .isTrue();
        assertThat(TurnObservation.of(log(judged("none", "none", 0.9), "no_answer_or_topic_change", false), false,
                MIN).resisted()).isTrue();
        // 답하기 어려워한 것 · 의아해한 것은 저항이 아니다. 규칙이 반응을 보지 않은 턴도 세지 않는다.
        assertThat(TurnObservation.of(log(judged("none", "none", 0.9), "struggle_to_answer", false), false, MIN)
                .resisted()).isFalse();
        assertThat(TurnObservation.of(log(judged("none", "none", 0.9), null, false), false, MIN).resisted())
                .isFalse();
    }

    @Test
    void countsOnlyExplicitRequestsForListeningOrAnotherView() {
        TurnObservation listen = TurnObservation.of(log(judged("listen", "clear", 0.9), null, false), false, MIN);
        assertThat(listen.askedToListen()).isTrue();
        assertThat(listen.askedOtherView()).isFalse();
        // 질문을 거부한 것도 들어 달라는 요청으로 본다(규칙과 같다).
        assertThat(TurnObservation.of(log(judged("none", "clear", 0.9), "refuse_question", false), false, MIN)
                .askedToListen()).isTrue();
        assertThat(TurnObservation.of(log(judged("other_view", "clear", 0.9), null, false), false, MIN)
                .askedOtherView()).isTrue();
        // 확신이 기준에 못 미치는 요청은 없는 것으로 본다.
        assertThat(TurnObservation.of(log(judged("listen", "clear", 0.4), null, false), false, MIN)
                .askedToListen()).isFalse();
    }

    @Test
    void readsTheAnswerToAFeelingQuestionOnlyRightAfterOne() {
        TurnJudgmentLog told = log(judged("none", "clear", 0.9), "agree_or_elaborate", false);
        assertThat(TurnObservation.of(told, true, MIN).feelingExpressed()).isTrue();
        // 기분을 묻지 않은 뒤의 답은 감정 강도 신호가 아니다.
        TurnObservation unasked = TurnObservation.of(told, false, MIN);
        assertThat(unasked.feelingExpressed()).isFalse();
        assertThat(unasked.feelingAvoided()).isFalse();

        assertThat(TurnObservation.of(log(judged("none", "none", 0.9), "agree_or_elaborate", false), true, MIN)
                .feelingAvoided()).isTrue();
        TurnObservation moved = TurnObservation.of(log(judged("none", "clear", 0.9), "no_answer_or_topic_change",
                false), true, MIN);
        assertThat(moved.feelingAvoided()).isTrue();
        assertThat(moved.feelingExpressed()).isFalse();
    }

    @Test
    void leavesVagueOrHardToAnswerRepliesOutOfBothSides() {
        TurnObservation vague = TurnObservation.of(log(judged("none", "vague", 0.9), "struggle_to_answer", false),
                true, MIN);
        assertThat(vague.feelingExpressed()).isFalse();
        assertThat(vague.feelingAvoided()).isFalse();
        // 확신이 낮은 감정 판단도 어느 쪽에도 세지 않는다.
        TurnObservation unsure = TurnObservation.of(log(judged("none", "none", 0.3), null, false), true, MIN);
        assertThat(unsure.feelingAvoided()).isFalse();
    }

    @Test
    void aFailedJudgmentStillCarriesWhetherBabaAskedAboutFeelings() {
        TurnObservation failed = TurnObservation.of(log(null, null, true), true, MIN);

        assertThat(failed.babaAskedFeeling()).isTrue();
        assertThat(failed).isEqualTo(new TurnObservation(false, false, false, false, false, true));
    }
}
