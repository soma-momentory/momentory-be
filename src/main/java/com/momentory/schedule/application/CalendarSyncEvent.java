package com.momentory.schedule.application;

import java.time.LocalDate;

public record CalendarSyncEvent(
        String externalId,
        LocalDate date,
        String title
) {
}
