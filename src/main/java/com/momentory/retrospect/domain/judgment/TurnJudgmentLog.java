package com.momentory.retrospect.domain.judgment;

import com.momentory.retrospect.domain.strategy.TurnStrategy;

/**
 * 한 턴의 판단 기록 — 어느 사용자 발화에 대한 판단인지, 그 결과, 그리고 판단으로 고른 전략. 발화 원문은 담지 않는다.
 *
 * @param messageId       판단한 사용자 발화의 메시지 id
 * @param diaryTurn       일기 작성 채팅에서 몇 번째 사용자 답변인가(1부터)
 * @param judgment        판단 결과. 판단 호출이 실패했으면 null
 * @param strategy        판단으로 고른 전략. 전략 선택을 켜지 않았거나 판단이 실패했으면 null
 * @param policyVersion   전략을 고른 규칙의 버전. 전략이 없으면 null
 * @param strategyApplied 바바의 응답이 실제로 그 전략대로 나갔는가 — 턴 상한·단답 체크인 등이 앞서면 false
 * @param generationFailed 이 턴의 문장 생성 모델 호출이 실패해 고정 문구로 응답했는가
 * @param askedFeeling    이 턴에 바바가 그때의 기분을 물었는가 — 다음 답변이 그 물음에 대한 반응이다. 전략 대화가 아니면 null
 */
public record TurnJudgmentLog(String messageId, int diaryTurn, TurnJudgment judgment,
        TurnStrategy strategy, String policyVersion, boolean strategyApplied, boolean generationFailed,
        Boolean askedFeeling) {

    public static TurnJudgmentLog of(String messageId, int diaryTurn, TurnJudgment judgment) {
        return new TurnJudgmentLog(messageId, diaryTurn, judgment, null, null, false, false, null);
    }

    public TurnJudgmentLog withStrategy(TurnStrategy strategy, String policyVersion, boolean applied) {
        return new TurnJudgmentLog(messageId, diaryTurn, judgment, strategy, policyVersion, applied,
                generationFailed, askedFeeling);
    }

    public TurnJudgmentLog withGenerationFailed() {
        return new TurnJudgmentLog(messageId, diaryTurn, judgment, strategy, policyVersion, strategyApplied, true,
                askedFeeling);
    }

    public TurnJudgmentLog withAskedFeeling(boolean asked) {
        return new TurnJudgmentLog(messageId, diaryTurn, judgment, strategy, policyVersion, strategyApplied,
                generationFailed, asked);
    }

    public boolean failed() {
        return judgment == null;
    }
}
