package com.momentory.schedule.presentation;

import com.momentory.schedule.application.CalendarSyncEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record CalendarSyncRequest(
        @Schema(example = "2026-07-01")
        @NotNull(message = "from은 필수입니다.")
        LocalDate from,
        @Schema(example = "2026-09-30")
        @NotNull(message = "to는 필수입니다.")
        LocalDate to,
        @NotNull(message = "events는 필수입니다.")
        List<@NotNull(message = "event는 필수입니다.") @Valid CalendarSyncEventRequest> events
) {

    List<CalendarSyncEvent> toEvents() {
        return events.stream().map(CalendarSyncEventRequest::toEvent).toList();
    }
}
