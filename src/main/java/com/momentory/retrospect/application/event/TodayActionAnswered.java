package com.momentory.retrospect.application.event;

import com.momentory.retrospect.domain.TodayActionResult;

/**
 * 밤 회고 첫 질문에서 오늘의 작은 일에 답했다. 편지 쪽이 <b>같은 트랜잭션에서 동기로</b> 받아
 * 작은 일의 결과와 바람카드의 완료 표시를 남긴다 — 기록이 실패하면 그 회고 턴째 롤백된다.
 */
public record TodayActionAnswered(Long letterActionId, TodayActionResult result) {
}
