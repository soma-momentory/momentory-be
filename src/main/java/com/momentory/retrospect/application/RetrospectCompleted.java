package com.momentory.retrospect.application;

import com.momentory.retrospect.domain.Emotion;

/**
 * 회고가 완료되며 일기를 남겼다는 <b>도메인 이벤트</b> — retrospect 컨텍스트가 발행하는 공개 사실.
 *
 * <p>일기 저장은 이제 이 이벤트를 구독하는 diary 컨텍스트가 맡는다(retrospect 는 일기를 어떻게
 * 저장하는지 모른다). 리스너는 <b>같은 트랜잭션에서 동기로</b> 처리되므로, 일기 저장이 실패하면
 * 회고 턴 전체가 롤백된다 — "일기 없는 완료 회고"가 생기지 않는다.
 *
 * <p>일기 본문이 없는 완료(안전 중단 등)에서는 발행하지 않는다.
 */
public record RetrospectCompleted(Long retrospectId, Long userId, Emotion currentEmotion,
        Emotion scheduleEmotion, String diary, String reframed) {
}
