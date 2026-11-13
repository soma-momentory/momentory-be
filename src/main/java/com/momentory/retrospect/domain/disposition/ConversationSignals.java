package com.momentory.retrospect.domain.disposition;

/**
 * 지난 대화 한 건에서 센 성향 신호 중 시작 성향을 정하는 데 쓰는 것.
 *
 * @param resistanceCount       저항 표지(답하지 않음 · 화제 전환 · 반박) 횟수
 * @param otherViewRequestCount 다른 관점을 원한다고 명시적으로 말한 횟수
 */
public record ConversationSignals(int resistanceCount, int otherViewRequestCount) {
}
