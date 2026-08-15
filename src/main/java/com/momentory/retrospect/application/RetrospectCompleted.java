package com.momentory.retrospect.application;

import com.momentory.retrospect.domain.Emotion;

/**
 * 회고 완료 턴이 만들어 낸 산출물을 알리는 <b>도메인 이벤트</b> — retrospect 컨텍스트가 발행하는
 * 공개 사실. 하나의 완료가 일기와(방향에 따라) 행동 카드를 낳으므로, 둘을 선택적으로 함께 싣는다.
 * 각 산출물의 저장은 이 이벤트를 구독하는 별도 컨텍스트(diary·actioncard)가 맡는다 — retrospect 는
 * 그것들을 어떻게 저장하는지 모른다.
 *
 * <p>리스너는 <b>같은 트랜잭션에서 동기로</b> 처리되므로, 저장이 실패하면 회고 턴 전체가 롤백된다
 * (산출물이 반쯤 저장돼 남는 일이 없다). 만들어진 산출물이 하나도 없으면 발행하지 않는다.
 *
 * @param diary      완료 턴에 나온 일기(없으면 null — 안전 중단 등)
 * @param actionCard 완료 턴에 나온 행동 카드(없으면 null — 카드가 없는 방향)
 */
public record RetrospectCompleted(Long retrospectId, Long userId, DiaryData diary,
        ActionCardData actionCard) {

    /** 일기 산출물 — 본문·리프레임과 진입 감정 두 종. */
    public record DiaryData(Emotion currentEmotion, Emotion scheduleEmotion, String original,
            String reframed) {
    }

    /** 행동 카드 산출물 — 상황·목표 행동과 '쉬는 방법 선호로 만든 카드인가' 내부 표식. */
    public record ActionCardData(String situation, String action, boolean fromRestPreference) {
    }
}
