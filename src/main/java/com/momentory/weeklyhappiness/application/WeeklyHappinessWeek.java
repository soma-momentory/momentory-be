package com.momentory.weeklyhappiness.application;

import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;

import java.time.LocalDate;
import java.util.Map;

/**
 * 한 주의 기록장 — 적은 칸만 든다(칸 순서대로). {@code firstWeekStart} 는 적은 칸이 하나라도 있는
 * 가장 이른 주이고, 아무것도 적지 않았으면 {@code null} 이다.
 */
public record WeeklyHappinessWeek(
        LocalDate weekStart,
        Map<WeeklyHappinessCategory, String> entries,
        LocalDate firstWeekStart
) {

    public WeeklyHappinessWeek {
        entries = Map.copyOf(entries);
    }
}
