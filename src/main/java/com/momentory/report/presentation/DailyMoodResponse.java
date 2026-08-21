package com.momentory.report.presentation;

import java.time.LocalDate;

import com.momentory.report.domain.DailyMood;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 「이번 주의 마음」 한 칸. 일기를 남기지 않은 날은 {@code emotion} 이 명시적으로 null 로 온다 —
 * 일곱 칸이 요일 자리에 그대로 대응해야 해서 필드를 빼지 않는다.
 */
public record DailyMoodResponse(
        @Schema(description = "그 날(KST)", example = "2026-08-17") LocalDate date,
        @Schema(description = "그날의 현재 감정 키 — 기록이 없으면 null", example = "calm")
        String emotion) {

    static DailyMoodResponse from(DailyMood mood) {
        return new DailyMoodResponse(mood.date(),
                mood.emotion() == null ? null : mood.emotion().key());
    }
}
