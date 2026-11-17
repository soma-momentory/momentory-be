package com.momentory.wardrobe.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @Test
    void movesOneCellPerDayAndStartsOverAfterTheSeventh() {
        assertThat(dayAfter(0)).isEqualTo(1);
        assertThat(dayAfter(2)).isEqualTo(3);
        assertThat(dayAfter(6)).isEqualTo(7);
        assertThat(dayAfter(7)).isEqualTo(1);
        assertThat(dayAfter(9)).isEqualTo(3);
        assertThat(Attendance.reward(3)).isEqualTo(20);
        assertThat(Attendance.reward(5)).isEqualTo(30);
        assertThat(Attendance.reward(7)).isEqualTo(50);
    }

    @Test
    void sameCellBeforeAndAfterTodaysCheckInAndAMissedDayStartsOver() {
        Set<LocalDate> days = new HashSet<>(Set.of(TODAY.minusDays(1), TODAY.minusDays(2)));
        assertThat(Attendance.dayOf(days, TODAY)).isEqualTo(3);
        days.add(TODAY);
        assertThat(Attendance.dayOf(days, TODAY)).isEqualTo(3);

        assertThat(Attendance.dayOf(Set.of(TODAY.minusDays(2), TODAY.minusDays(3)), TODAY)).isEqualTo(1);
    }

    /** 어제까지 {@code run} 일을 이어 온 날의 칸 */
    private static int dayAfter(int run) {
        Set<LocalDate> days = IntStream.rangeClosed(1, run).mapToObj(TODAY::minusDays).collect(Collectors.toSet());
        return Attendance.dayOf(days, TODAY);
    }
}
