package com.momentory.letter.presentation;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ChooseLetterActionRequest(
        @Schema(description = "고를 칸 0~2. null 이면 선택을 푼다", example = "1", nullable = true)
        @Min(value = 0, message = "작은 일 칸은 0부터 2까지입니다.")
        @Max(value = 2, message = "작은 일 칸은 0부터 2까지입니다.")
        Integer position,
        @Schema(description = "고른 「언제」 칩의 말", example = "점심 뒤", nullable = true)
        @Size(max = 30, message = "「언제」는 최대 30자입니다.")
        String whenLabel,
        @Schema(description = "일정 칩을 골랐을 때 그 일정 ID", example = "41", nullable = true)
        Long scheduleId,
        @Schema(description = "알림 시각(ISO-8601). 없으면 알림 없이", example = "2026-10-27T03:30:00Z", nullable = true)
        Instant remindAt
) {
}
