package com.momentory.retrospect.application;

import java.util.List;

import com.momentory.retrospect.domain.Emotion;

/**
 * 회고 완료 턴이 만들어 낸 산출물을 알리는 <b>도메인 이벤트</b> — retrospect 컨텍스트가 발행하는
 * 공개 사실. 하나의 완료가 일기와(감정 탐색을 거친 경우) 바람 카드를 낳으므로, 둘을 선택적으로
 * 함께 싣는다. 각 산출물의 저장은 이 이벤트를 구독하는 별도 컨텍스트(diary·actioncard)가 맡는다.
 *
 * <p>리스너는 <b>같은 트랜잭션에서 동기로</b> 처리되므로, 저장이 실패하면 회고 턴 전체가 롤백된다.
 * 만들어진 산출물이 하나도 없으면 발행하지 않는다.
 *
 * @param diary    완료 턴에 나온 일기(없으면 null)
 * @param wishCard 완료 턴에 나온 바람 카드(감정 탐색을 안 거쳤으면 null)
 */
public record RetrospectCompleted(Long retrospectId, Long userId, DiaryData diary,
        WishCardData wishCard) {

    /**
     * 일기 산출물 — 본문·리프레임 + 대표 감정({@code currentEmotion}, 리포트용) + 감정 태그
     * ({@code emotions}, v2 일기에서 드러난 감정 전체). 감정 없이 끝난 일기면 대표 감정 null·태그 빈 목록.
     */
    public record DiaryData(Emotion currentEmotion, Emotion scheduleEmotion, String original,
            String reframed, List<Emotion> emotions) {

        public DiaryData {
            emotions = emotions == null ? List.of() : List.copyOf(emotions);
        }
    }

    /**
     * 바람 카드 산출물 (채팅흐름_v2, 결정 ①로 기존 행동 카드 대체) — 상황·감정·바람·바랐던 모습·작은
     * 행동·감정 성격. 비운 항목은 null/빈 목록.
     */
    public record WishCardData(String situation, List<Emotion> emotions, List<String> needWords,
            String desiredState, String smallAction, String sentiment) {

        public WishCardData {
            emotions = emotions == null ? List.of() : List.copyOf(emotions);
            needWords = needWords == null ? List.of() : List.copyOf(needWords);
        }
    }
}
