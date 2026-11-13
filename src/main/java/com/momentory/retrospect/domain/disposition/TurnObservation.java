package com.momentory.retrospect.domain.disposition;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;

/**
 * 사용자 답변 하나에서 읽은 성향 신호 — 대화별 관찰에 더해진다. 성향을 정하지는 않는다(기준은 검증 뒤에 정한다).
 *
 * <ul>
 * <li><b>주도성</b>: 바바의 질문 · 제안 뒤의 저항(답하지 않음 · 화제 전환 · 반박). 규칙이 실제로 본 반응만 센다 —
 *     첫 질문이나 「더 나누고 싶은 이야기를 들려주세요」 뒤의 답은 반응으로 보지 않는다.
 * <li><b>지지 대 도전</b>: 명시적 요청만 — 들어 달라고 했는가(질문 거부 포함), 다른 관점을 원했는가.
 * <li><b>감정 강도</b>: 바바가 기분을 물은 바로 다음 답에서, 감정을 분명히 말했는가 / 감정 없이 답하거나 다른 얘기로
 *     넘어갔는가. 모호하게 말했거나 답하기 어려워한 것은 어느 쪽에도 세지 않는다.
 * </ul>
 *
 * @param resisted         저항 표지가 나왔는가
 * @param askedToListen    들어 달라고 했는가
 * @param askedOtherView   다른 관점을 원했는가
 * @param feelingExpressed 기분을 물은 뒤 감정을 분명히 말했는가
 * @param feelingAvoided   기분을 물은 뒤 감정 없이 답하거나 다른 얘기로 넘어갔는가
 * @param babaAskedFeeling 이 턴에 바바가 기분을 물었는가 — 다음 답이 그 물음에 대한 반응이 된다
 */
public record TurnObservation(boolean resisted, boolean askedToListen, boolean askedOtherView,
        boolean feelingExpressed, boolean feelingAvoided, boolean babaAskedFeeling) {

    /**
     * @param afterFeelingQuestion 이 답변이 바바의 기분 질문 바로 뒤에 나왔는가
     * @param minConfidence        이 값 미만의 판단은 없는 것으로 본다(전략 규칙과 같은 기준)
     */
    public static TurnObservation of(TurnJudgmentLog log, boolean afterFeelingQuestion, double minConfidence) {
        // 규칙이 실제로 본 반응 — 확신이 낮거나 반응을 볼 자리가 아니었으면 null 이다.
        String reaction = log.strategy() == null ? null : log.strategy().reaction();
        String requested = log.failed() ? null : choice(log, JudgmentItem.REQUESTED_MODE, minConfidence);
        String emotion = log.failed() ? null : choice(log, JudgmentItem.EMOTION_EXPRESSION, minConfidence);
        boolean changedTopic = "no_answer_or_topic_change".equals(reaction);
        return new TurnObservation(
                StrategyPolicy.isResistance(reaction),
                "listen".equals(requested) || "refuse_question".equals(reaction),
                "other_view".equals(requested),
                afterFeelingQuestion && "clear".equals(emotion) && !changedTopic,
                afterFeelingQuestion && ("none".equals(emotion) || changedTopic),
                Boolean.TRUE.equals(log.askedFeeling()));
    }

    private static String choice(TurnJudgmentLog log, JudgmentItem item, double minConfidence) {
        ItemJudgment j = log.judgment().of(item);
        return j.confidence() < minConfidence ? null : j.choice();
    }
}
