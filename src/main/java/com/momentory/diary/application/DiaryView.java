package com.momentory.diary.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.diary.domain.Diary;

/**
 * 일기 조회 결과 한 벌 — 목록·단건 응답의 재료다. 바람 카드는 그날의 회고
 * ({@code retrospectId}) 로 별도 조회한다.
 *
 * <p>{@code currentEmotion} 은 대표 감정(리포트용, 없을 수 있음), {@code emotions} 는 v2 감정 태그
 * (일기에서 드러난 감정 키 전체 — 저장된 CSV 를 나눈 것).
 */
public record DiaryView(Long id, Long retrospectId, Emotion currentEmotion,
        Emotion scheduleEmotion, List<String> emotions, String original, String reframed,
        Instant createdAt) {

    public DiaryView {
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
    }

    static DiaryView from(Diary diary) {
        return new DiaryView(diary.getId(), diary.getRetrospectId(), diary.getCurrentEmotion(),
                diary.getScheduleEmotion(), splitCsv(diary.getEmotions()), diary.getOriginal(),
                diary.getReframed(), diary.getCreatedAt());
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
