package com.momentory.retrospect.application;

import java.util.List;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.TopicType;

/**
 * 회고 완료 턴이 만들어 낸 산출물을 알리는 <b>도메인 이벤트</b> — retrospect 컨텍스트가 발행하는
 * 공개 사실. 하나의 완료가 일기와(감정 탐색을 거친 경우) 바람 카드, 그리고 채팅에서 뽑은 토픽
 * (주 일정·키워드 + 매칭 감정)을 낳으므로, 셋을 선택적으로 함께 싣는다. 각 산출물의 저장은 이
 * 이벤트를 구독하는 별도 컨텍스트(diary·actioncard·retrospecttopic)가 맡는다.
 *
 * <p>리스너는 <b>같은 트랜잭션에서 동기로</b> 처리되므로, 저장이 실패하면 회고 턴 전체가 롤백된다.
 * 만들어진 산출물이 하나도 없으면 발행하지 않는다.
 *
 * @param diary    완료 턴에 나온 일기(없으면 null)
 * @param wishCard 완료 턴에 나온 바람 카드(감정 탐색을 안 거쳤으면 null)
 * @param topics   채팅에서 뽑은 토픽들(주 일정·키워드). 없으면 빈 목록
 */
public record RetrospectCompleted(Long retrospectId, Long userId, DiaryData diary,
        WishCardData wishCard, List<TopicData> topics) {

    public RetrospectCompleted {
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /**
     * 일기 산출물 — 본문(하나) + 대표 감정({@code primaryEmotion}, 리포트용) + 감정 태그
     * ({@code emotions}, v2 일기에서 드러난 감정 전체). 감정 없이 끝난 일기면 대표 감정 null·태그 빈 목록.
     */
    public record DiaryData(Emotion primaryEmotion, String original, List<Emotion> emotions) {

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

    /**
     * 토픽 산출물 하나 — 주 일정({@code SCHEDULE}, 목록 일정이면 {@code scheduleId} 있음) 또는 키워드
     * ({@code KEYWORD}). {@code label} 은 항상 텍스트, {@code emotions} 는 그 토픽에 매칭된 감정(0~다수).
     */
    public record TopicData(TopicType type, Long scheduleId, String label, List<Emotion> emotions) {

        public TopicData {
            emotions = emotions == null ? List.of() : List.copyOf(emotions);
        }
    }
}
