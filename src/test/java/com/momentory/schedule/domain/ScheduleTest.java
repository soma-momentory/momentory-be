package com.momentory.schedule.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTest {

    @Test
    void createsAndUpdatesManualScheduleWithTrimmedTitle() {
        Schedule schedule = Schedule.createManual(1L, LocalDate.of(2026, 8, 10), " 운동하기 ", 0L);

        schedule.update(LocalDate.of(2026, 8, 11), " 저녁 운동하기 ");

        assertThat(schedule.getScheduleDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(schedule.getTitle()).isEqualTo("저녁 운동하기");
        assertThat(schedule.isCompleted()).isFalse();
        assertThat(schedule.getEmotion()).isNull();
        assertThat(schedule.getSource()).isEqualTo(ScheduleSource.MANUAL);
        assertThat(schedule.getExternalId()).isNull();
        assertThat(schedule.isHidden()).isFalse();
    }

    @Test
    void synchronizesAndRestoresCalendarScheduleWithoutClearingUserState() {
        Schedule schedule = Schedule.createCalendar(
                1L, "calendar-event-1", LocalDate.of(2026, 8, 10), "회의", 0L
        );
        schedule.changeCompletion(true, ScheduleEmotion.CALM);
        schedule.changeHidden(true);
        schedule.delete(Instant.parse("2026-08-11T00:00:00Z"));

        boolean changed = schedule.syncFromCalendar(LocalDate.of(2026, 8, 12), "팀 회의");

        assertThat(changed).isTrue();
        assertThat(schedule.getSource()).isEqualTo(ScheduleSource.CALENDAR);
        assertThat(schedule.getExternalId()).isEqualTo("calendar-event-1");
        assertThat(schedule.getScheduleDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(schedule.getTitle()).isEqualTo("팀 회의");
        assertThat(schedule.isDeleted()).isFalse();
        assertThat(schedule.isCompleted()).isTrue();
        assertThat(schedule.getEmotion()).isEqualTo(ScheduleEmotion.CALM);
        assertThat(schedule.isHidden()).isTrue();
    }

    @Test
    void rejectsCalendarOnlyOperationsForManualSchedule() {
        Schedule schedule = Schedule.createManual(1L, LocalDate.now(), "운동하기", 0L);

        assertThatThrownBy(() -> schedule.syncFromCalendar(LocalDate.now(), "회의"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> schedule.changeHidden(true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankAndTooLongTitles() {
        assertThatThrownBy(() -> Schedule.createManual(1L, LocalDate.now(), "   ", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Schedule.createManual(1L, LocalDate.now(), "a".repeat(256), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsDeletionOnlyOnce() {
        Schedule schedule = Schedule.createManual(1L, LocalDate.now(), "운동하기", 0L);
        Instant firstDeletedAt = Instant.parse("2026-08-10T00:00:00Z");

        schedule.delete(firstDeletedAt);
        schedule.delete(firstDeletedAt.plusSeconds(1));

        assertThat(schedule.isDeleted()).isTrue();
        assertThat(schedule.getDeletedAt()).isEqualTo(firstDeletedAt);
    }

    @Test
    void changesCompletionAndClearsEmotionWhenCompletionIsCancelled() {
        Schedule schedule = Schedule.createManual(1L, LocalDate.now(), "운동하기", 0L);

        schedule.changeCompletion(true, ScheduleEmotion.PROUD);

        assertThat(schedule.isCompleted()).isTrue();
        assertThat(schedule.getEmotion()).isEqualTo(ScheduleEmotion.PROUD);

        schedule.changeCompletion(false, null);

        assertThat(schedule.isCompleted()).isFalse();
        assertThat(schedule.getEmotion()).isNull();
    }

    @Test
    void rejectsEmotionForIncompleteSchedule() {
        Schedule schedule = Schedule.createManual(1L, LocalDate.now(), "운동하기", 0L);

        assertThatThrownBy(() -> schedule.changeCompletion(false, ScheduleEmotion.PROUD))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
