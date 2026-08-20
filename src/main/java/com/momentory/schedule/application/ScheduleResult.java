package com.momentory.schedule.application;

import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.domain.ScheduleSource;

import java.time.LocalDate;

public record ScheduleResult(
        Long id,
        LocalDate date,
        String title,
        boolean completed,
        ScheduleEmotion emotion,
        long displayOrder,
        ScheduleSource source,
        boolean hidden
) {

    public static ScheduleResult from(Schedule schedule) {
        return new ScheduleResult(
                schedule.getId(),
                schedule.getScheduleDate(),
                schedule.getTitle(),
                schedule.isCompleted(),
                schedule.getEmotion(),
                schedule.getDisplayOrder(),
                schedule.getSource(),
                schedule.isHidden()
        );
    }
}
