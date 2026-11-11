package com.momentory.retrospect.domain.judgment;

/**
 * 한 턴의 판단 기록 — 어느 사용자 발화에 대한 판단인지와 그 결과. 발화 원문은 담지 않는다.
 *
 * @param messageId 판단한 사용자 발화의 메시지 id
 * @param diaryTurn 일기 작성 채팅에서 몇 번째 사용자 답변인가(1부터)
 * @param judgment  판단 결과. 판단 호출이 실패했으면 null
 */
public record TurnJudgmentLog(String messageId, int diaryTurn, TurnJudgment judgment) {

    public boolean failed() {
        return judgment == null;
    }
}
