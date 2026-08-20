package com.momentory.schedule.presentation;

import com.momentory.schedule.application.ScheduleResult;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.domain.ScheduleSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record ScheduleResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "2026-08-10") LocalDate date,
        @Schema(example = "운동하기") String title,
        @Schema(example = "false") boolean completed,
        @Schema(nullable = true, example = "HAPPY") ScheduleEmotion emotion,
        @Schema(example = "0") long displayOrder,
        @Schema(example = "MANUAL") ScheduleSource source,
        @Schema(example = "false") boolean hidden
) {

    static ScheduleResponse from(ScheduleResult result) {
        return new ScheduleResponse(
                result.id(),
                result.date(),
                result.title(),
                result.completed(),
                result.emotion(),
                result.displayOrder(),
                result.source(),
                result.hidden()
        );
    }
}
