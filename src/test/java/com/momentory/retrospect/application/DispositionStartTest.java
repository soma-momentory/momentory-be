package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DispositionStartTest {

    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 18);

    @Test
    void theFirstThreeDaysAreEarlyAndTheFourthIsNot() {
        assertThat(DispositionStart.withinEarlyDays(FIRST_DAY, FIRST_DAY, 3)).isTrue();
        assertThat(DispositionStart.withinEarlyDays(FIRST_DAY, FIRST_DAY.plusDays(2), 3)).isTrue();
        assertThat(DispositionStart.withinEarlyDays(FIRST_DAY, FIRST_DAY.plusDays(3), 3)).isFalse();
    }
}
