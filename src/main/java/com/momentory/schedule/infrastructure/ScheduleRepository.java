package com.momentory.schedule.infrastructure;

import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.domain.ScheduleSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(Long userId, LocalDate scheduleDate);

    Optional<Schedule> findTopByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderDesc(Long userId, LocalDate scheduleDate);

    Optional<Schedule> findByIdAndUserId(Long id, Long userId);

    Optional<Schedule> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Schedule> findByIdAndUserIdAndSource(Long id, Long userId, ScheduleSource source);

    Optional<Schedule> findByIdAndUserIdAndSourceAndDeletedAtIsNull(Long id, Long userId, ScheduleSource source);

    List<Schedule> findByUserIdAndSourceAndExternalIdIn(
            Long userId,
            ScheduleSource source,
            Collection<String> externalIds
    );

    List<Schedule> findByUserIdAndSourceAndScheduleDateBetweenAndDeletedAtIsNull(
            Long userId,
            ScheduleSource source,
            LocalDate from,
            LocalDate to
    );

    List<Schedule> findByUserIdAndScheduleDateBetweenAndDeletedAtIsNull(
            Long userId,
            LocalDate from,
            LocalDate to
    );

    List<Schedule> findByUserIdAndScheduleDateBetweenAndHiddenFalseAndDeletedAtIsNullOrderByScheduleDateAscDisplayOrderAscIdAsc(
            Long userId,
            LocalDate from,
            LocalDate to
    );
}
