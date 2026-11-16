package com.momentory.retrospect.application.event;

/**
 * 오늘의 작은 일을 해 보니 어땠는지 답했다. 편지 쪽이 <b>같은 트랜잭션에서 동기로</b> 받아 바람카드의 느낀 점으로
 * 남긴다 — 기록이 실패하면 그 회고 턴째 롤백된다.
 *
 * @param reflection 사용자의 답을 한 줄로 줄인 느낀 점
 */
public record TodayActionReflected(Long letterActionId, String reflection) {
}
