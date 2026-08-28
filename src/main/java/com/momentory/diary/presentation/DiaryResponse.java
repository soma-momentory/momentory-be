package com.momentory.diary.presentation;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.diary.application.DiaryView;
import com.momentory.retrospect.domain.Emotion;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 일기 한 벌 조회 응답 — 목록의 항목이자 단건 조회의 본체. 목록도 전체 본문
 * ({@code original}/{@code reframed}) 을 그대로 담는다.
 *
 * <p>{@code reframedDiary} 는 짧은 기록형에서 없을 수 있다. 행동 카드는 {@code retrospectId} 로
 * {@code GET /retrospect/{id}/diary} 에서 가져온다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiaryResponse(
        @Schema(description = "일기 id", example = "1") Long id,
        @Schema(description = "그날의 회고 id — 행동 카드 조회에 쓴다", example = "10") Long retrospectId,
        @Schema(description = "대표 감정 키 — 없을 수 있음", example = "depressed") String currentEmotion,
        @Schema(description = "일정 감정 키 — 없을 수 있음", example = "anxious") String scheduleEmotion,
        @Schema(description = "감정 태그 키 목록(일기에서 드러난 감정 전체)", example = "[\"angry\", \"frustrated\"]") List<String> emotions,
        @Schema(description = "일기 본문") String original,
        @Schema(description = "다시 본 오늘(리프레임) — 없을 수 있음") String reframed,
        @Schema(description = "작성 시각(생성 시기)", example = "2026-08-14T02:23:47.850Z") Instant createdAt) {

    static DiaryResponse from(DiaryView view) {
        return new DiaryResponse(view.id(), view.retrospectId(),
                keyOf(view.currentEmotion()), keyOf(view.scheduleEmotion()), view.emotions(),
                view.original(), view.reframed(), view.createdAt());
    }

    private static String keyOf(Emotion emotion) {
        return emotion == null ? null : emotion.key();
    }
}
