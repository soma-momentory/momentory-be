package com.momentory.weeklyhappiness.presentation;

import com.momentory.weeklyhappiness.application.WeeklyHappinessWeek;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** 한 주의 기록장 — 적은 칸만, 칸 순서(MOMENT → REST)대로. 아무것도 적지 않았으면 빈 배열. */
public record WeeklyHappinessResponse(
        @Schema(description = "그 주의 일요일(KST · 일요일 04:00 에 주가 바뀐다)", example = "2026-11-01") LocalDate weekStart,
        @ArraySchema(arraySchema = @Schema(description = "적은 칸들 — 비운 칸은 오지 않는다")) List<WeeklyHappinessEntryResponse> entries,
        @Schema(description = "적은 칸이 하나라도 있는 가장 이른 주 — 아무 주에도 적지 않았으면 null", nullable = true, example = "2026-10-11")
        LocalDate firstWeekStart
) {

    static WeeklyHappinessResponse from(WeeklyHappinessWeek week) {
        List<WeeklyHappinessEntryResponse> entries = Arrays.stream(WeeklyHappinessCategory.values())
                .filter(week.entries()::containsKey)
                .map(category -> new WeeklyHappinessEntryResponse(category, week.entries().get(category)))
                .toList();
        return new WeeklyHappinessResponse(week.weekStart(), entries, week.firstWeekStart());
    }

    public record WeeklyHappinessEntryResponse(
            @Schema(description = "칸", example = "FOOD") WeeklyHappinessCategory category,
            @Schema(description = "적은 글", example = "오랜만에 먹은 마라탕") String content
    ) {
    }
}
