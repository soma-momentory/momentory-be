package com.momentory.retrospect.application;

import java.time.Instant;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.infrastructure.persistence.Diary;

/**
 * 일기 조회 결과 한 벌 — 목록·단건 응답의 재료다. 행동 카드는 그날의 회고
 * ({@code retrospectId}) 로 별도 조회한다({@code GET /retrospect/{id}/diary}).
 */
public record DiaryView(Long id, Long retrospectId, Emotion currentEmotion,
        Emotion scheduleEmotion, String original, String reframed, Instant createdAt) {

    static DiaryView from(Diary diary) {
        return new DiaryView(diary.getId(), diary.getRetrospectId(), diary.getCurrentEmotion(),
                diary.getScheduleEmotion(), diary.getOriginal(), diary.getReframed(),
                diary.getCreatedAt());
    }
}
