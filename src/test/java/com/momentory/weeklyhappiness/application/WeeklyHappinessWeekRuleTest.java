package com.momentory.weeklyhappiness.application;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 쓰는 것은 이번 주뿐 · 지난 주는 읽기만 — 주는 일요일 04:00(KST)에 바뀐다. */
class WeeklyHappinessWeekRuleTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 11, 1);       // 일요일
    private static final LocalDate NEXT_WEEK = LocalDate.of(2026, 11, 8);  // 다음 일요일

    @Test
    void saturdayNightWeekStaysWritableUntilSunday0359AndBecomesPastAt0400() {
        Instant saturdayNight = kst(2026, 11, 7, 23, 30);
        Instant sunday0359 = kst(2026, 11, 8, 3, 59);
        Instant sunday0400 = kst(2026, 11, 8, 4, 0);

        assertThatCode(() -> WeeklyHappinessService.requireWritableWeek(WEEK, saturdayNight)).doesNotThrowAnyException();
        assertThatCode(() -> WeeklyHappinessService.requireWritableWeek(WEEK, sunday0359)).doesNotThrowAnyException();
        assertCode(() -> WeeklyHappinessService.requireWritableWeek(NEXT_WEEK, sunday0359), "WEEKLY_HAPPINESS_FUTURE_WEEK");

        assertCode(() -> WeeklyHappinessService.requireWritableWeek(WEEK, sunday0400), "WEEKLY_HAPPINESS_PAST_WEEK");
        assertThatCode(() -> WeeklyHappinessService.requireWritableWeek(NEXT_WEEK, sunday0400)).doesNotThrowAnyException();
        // 지난 주가 되어도 읽기는 된다
        assertThatCode(() -> WeeklyHappinessService.requireReadableWeek(WEEK, sunday0400)).doesNotThrowAnyException();
    }

    @Test
    void readingAllowsPastAndCurrentButNotFutureOrNonSunday() {
        Instant wednesday = kst(2026, 11, 4, 12, 0);

        assertThatCode(() -> WeeklyHappinessService.requireReadableWeek(WEEK.minusWeeks(10), wednesday)).doesNotThrowAnyException();
        assertThatCode(() -> WeeklyHappinessService.requireReadableWeek(WEEK, wednesday)).doesNotThrowAnyException();
        assertCode(() -> WeeklyHappinessService.requireReadableWeek(NEXT_WEEK, wednesday), "WEEKLY_HAPPINESS_FUTURE_WEEK");
        assertCode(() -> WeeklyHappinessService.requireReadableWeek(WEEK.plusDays(1), wednesday), "WEEKLY_HAPPINESS_INVALID_WEEK_START");
        assertCode(() -> WeeklyHappinessService.requireWritableWeek(WEEK.minusDays(1), wednesday), "WEEKLY_HAPPINESS_INVALID_WEEK_START");
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(WeeklyHappinessException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static Instant kst(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.ofHours(9)).toInstant();
    }
}
