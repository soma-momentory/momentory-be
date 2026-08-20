package com.momentory.schedule.presentation;

import com.momentory.schedule.application.CalendarSyncEvent;
import com.momentory.schedule.domain.Schedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CalendarSyncEventRequest(
        @Schema(example = "6b0a329de1f04bf0")
        @NotBlank(message = "externalId는 필수입니다.")
        @Size(max = 255, message = "externalId는 최대 255자입니다.")
        String externalId,
        @Schema(example = "2026-08-17")
        @NotNull(message = "date는 필수입니다.")
        LocalDate date,
        @Schema(example = "회의 참석")
        @NotBlank(message = "title은 필수입니다.")
        @Size(max = Schedule.TITLE_MAX_LENGTH, message = "title은 최대 255자입니다.")
        String title
) {

    public CalendarSyncEventRequest {
        externalId = externalId == null ? null : externalId.strip();
        title = title == null ? null : title.strip();
    }

    CalendarSyncEvent toEvent() {
        return new CalendarSyncEvent(externalId, date, title);
    }
}
