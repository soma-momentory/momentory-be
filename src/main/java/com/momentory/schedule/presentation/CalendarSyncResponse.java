package com.momentory.schedule.presentation;

import com.momentory.schedule.application.CalendarSyncResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CalendarSyncResponse(
        @Schema(example = "3") int created,
        @Schema(example = "1") int updated,
        @Schema(example = "1") int deleted
) {

    static CalendarSyncResponse from(CalendarSyncResult result) {
        return new CalendarSyncResponse(result.created(), result.updated(), result.deleted());
    }
}
