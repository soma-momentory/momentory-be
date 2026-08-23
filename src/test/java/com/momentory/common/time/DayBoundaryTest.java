package com.momentory.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 하루 경계(KST · 04:00) 계산 규칙 — 자정이 아니라 새벽 4시에 날짜가 넘어간다.
 */
class DayBoundaryTest {

    @Test
    @DisplayName("04:00 전(KST)은 아직 어제, 04:00 부터 오늘이다")
    void rollsAtFourAmKst() {
        // 2026-08-14 03:59:59 KST = 2026-08-13T18:59:59Z → 아직 8/13
        assertThat(DayBoundary.toLocalDate(Instant.parse("2026-08-13T18:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 8, 13));
        // 2026-08-14 04:00:00 KST = 2026-08-13T19:00:00Z → 8/14
        assertThat(DayBoundary.toLocalDate(Instant.parse("2026-08-13T19:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    @DisplayName("한낮은 그날에 속한다 — 자정~04:00 밖은 달력 날짜 그대로")
    void middayBelongsToSameDay() {
        // 2026-08-14 12:00 KST = 2026-08-14T03:00:00Z
        assertThat(DayBoundary.toLocalDate(Instant.parse("2026-08-14T03:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    @DisplayName("하루의 시작은 그날 04:00 KST 다")
    void startOfDayIsFourAmKst() {
        // 2026-08-14 04:00 KST = 2026-08-13T19:00:00Z
        assertThat(DayBoundary.startOfDay(LocalDate.of(2026, 8, 14)))
                .isEqualTo(Instant.parse("2026-08-13T19:00:00Z"));
    }

    @Test
    @DisplayName("startOfDay 로 자른 구간의 시작 순간은 그날에 속한다 — 반열림 구간의 정합")
    void startOfDayInstantBelongsToThatDay() {
        LocalDate date = LocalDate.of(2026, 8, 14);
        assertThat(DayBoundary.toLocalDate(DayBoundary.startOfDay(date))).isEqualTo(date);
    }
}
