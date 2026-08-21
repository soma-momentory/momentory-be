package com.momentory.actioncard.application;

/**
 * 한 구간에 만들어진 행동 카드 수와 그중 "해봤어요"까지 간 카드 수 — 주간 리포트의 「이번 주 한눈에」가
 * 쓴다. {@code completedCount} 는 <b>그 구간에 만들어진 카드 중</b> 완료된 것만 센다(완료 시각이 아니라
 * 생성 시각이 구간 안이어야 한다).
 */
public record ActionCardPeriodCount(long createdCount, long completedCount) {
}
