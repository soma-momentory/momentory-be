package com.momentory.schedule.application;

import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.domain.ScheduleSource;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    public ScheduleService(ScheduleRepository scheduleRepository, UserRepository userRepository) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
    }

    private static final int MAX_PERIOD_DAYS = 366;

    @Transactional(readOnly = true)
    public List<ScheduleResult> getSchedules(Long userId, LocalDate date) {
        requireUser(userId);
        return scheduleRepository.findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(userId, date)
                .stream()
                .map(ScheduleResult::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScheduleResult> getSchedulesInPeriod(Long userId, LocalDate from, LocalDate to) {
        requireUser(userId);
        validatePeriod(from, to);
        return scheduleRepository
                .findByUserIdAndScheduleDateBetweenAndHiddenFalseAndDeletedAtIsNullOrderByScheduleDateAscDisplayOrderAscIdAsc(
                        userId, from, to
                )
                .stream()
                .map(ScheduleResult::from)
                .toList();
    }

    @Transactional
    public ScheduleResult createManualSchedule(Long userId, LocalDate date, String title) {
        requireUser(userId);
        long displayOrder = scheduleRepository
                .findTopByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderDesc(userId, date)
                .map(schedule -> schedule.getDisplayOrder() + 1)
                .orElse(0L);

        Schedule schedule = scheduleRepository.save(Schedule.createManual(userId, date, title, displayOrder));
        return ScheduleResult.from(schedule);
    }

    @Transactional
    public ScheduleResult updateManualSchedule(Long userId, Long scheduleId, LocalDate date, String title) {
        requireUser(userId);
        Schedule schedule = scheduleRepository.findByIdAndUserIdAndSourceAndDeletedAtIsNull(
                        scheduleId, userId, ScheduleSource.MANUAL
                )
                .orElseThrow(ScheduleNotFoundException::new);
        schedule.update(date, title);
        return ScheduleResult.from(schedule);
    }

    @Transactional
    public void deleteSchedule(Long userId, Long scheduleId) {
        requireUser(userId);
        Schedule schedule = scheduleRepository.findByIdAndUserIdAndSource(scheduleId, userId, ScheduleSource.MANUAL)
                .orElseThrow(ScheduleNotFoundException::new);
        schedule.delete(Instant.now());
    }

    @Transactional
    public void changeCalendarVisibility(Long userId, Long scheduleId, boolean hidden) {
        requireUser(userId);
        Schedule schedule = scheduleRepository.findByIdAndUserIdAndSourceAndDeletedAtIsNull(
                        scheduleId, userId, ScheduleSource.CALENDAR
                )
                .orElseThrow(ScheduleNotFoundException::new);
        schedule.changeHidden(hidden);
    }

    @Transactional
    public CalendarSyncResult syncCalendar(
            Long userId,
            LocalDate from,
            LocalDate to,
            List<CalendarSyncEvent> events
    ) {
        requireUser(userId);
        validateSyncRequest(from, to, events);

        Map<String, CalendarSyncEvent> incomingByExternalId = events.stream()
                .collect(Collectors.toMap(
                        CalendarSyncEvent::externalId,
                        Function.identity(),
                        (left, right) -> {
                            throw new InvalidCalendarSyncException();
                        },
                        LinkedHashMap::new
                ));

        Map<String, Schedule> existingByExternalId = incomingByExternalId.isEmpty()
                ? Map.of()
                : scheduleRepository.findByUserIdAndSourceAndExternalIdIn(
                                userId, ScheduleSource.CALENDAR, incomingByExternalId.keySet()
                        )
                        .stream()
                        .collect(Collectors.toMap(Schedule::getExternalId, Function.identity()));

        List<Schedule> activeCalendarSchedules = scheduleRepository
                .findByUserIdAndSourceAndScheduleDateBetweenAndDeletedAtIsNull(
                        userId, ScheduleSource.CALENDAR, from, to
                );
        Map<LocalDate, Long> nextDisplayOrder = nextDisplayOrders(
                scheduleRepository.findByUserIdAndScheduleDateBetweenAndDeletedAtIsNull(userId, from, to)
        );

        int created = 0;
        int updated = 0;
        List<Schedule> newSchedules = new ArrayList<>();
        List<CalendarSyncEvent> orderedEvents = incomingByExternalId.values().stream()
                .sorted(Comparator.comparing(CalendarSyncEvent::date)
                        .thenComparing(CalendarSyncEvent::title)
                        .thenComparing(CalendarSyncEvent::externalId))
                .toList();
        for (CalendarSyncEvent event : orderedEvents) {
            Schedule existing = existingByExternalId.get(event.externalId());
            if (existing != null) {
                if (existing.syncFromCalendar(event.date(), event.title())) {
                    updated++;
                }
                continue;
            }
            long displayOrder = nextDisplayOrder.compute(
                    event.date(),
                    (date, next) -> next == null ? 1L : next + 1L
            ) - 1L;
            newSchedules.add(Schedule.createCalendar(
                    userId, event.externalId(), event.date(), event.title(), displayOrder
            ));
            created++;
        }
        scheduleRepository.saveAll(newSchedules);

        Instant deletedAt = Instant.now();
        int deleted = 0;
        for (Schedule schedule : activeCalendarSchedules) {
            if (!incomingByExternalId.containsKey(schedule.getExternalId())) {
                schedule.delete(deletedAt);
                deleted++;
            }
        }

        return new CalendarSyncResult(created, updated, deleted);
    }

    @Transactional
    public ScheduleCompletionResult changeCompletion(Long userId, Long scheduleId, boolean completed, ScheduleEmotion emotion) {
        requireUser(userId);
        Schedule schedule = scheduleRepository.findByIdAndUserIdAndDeletedAtIsNull(scheduleId, userId)
                .orElseThrow(ScheduleNotFoundException::new);
        schedule.changeCompletion(completed, emotion);
        return ScheduleCompletionResult.from(schedule);
    }

    @Transactional
    public void changeScheduleOrder(Long userId, LocalDate date, List<Long> scheduleIds) {
        requireUser(userId);
        if (scheduleIds.stream().distinct().count() != scheduleIds.size()) {
            throw new InvalidScheduleOrderException();
        }

        List<Schedule> requestedSchedules = scheduleRepository.findAllById(scheduleIds);
        if (requestedSchedules.size() != scheduleIds.size()) {
            throw new InvalidScheduleOrderException();
        }
        if (requestedSchedules.stream().anyMatch(schedule -> !schedule.belongsTo(userId))) {
            throw new ScheduleNotFoundException();
        }
        if (requestedSchedules.stream().anyMatch(Schedule::isDeleted)
                || requestedSchedules.stream().anyMatch(schedule -> !date.equals(schedule.getScheduleDate()))) {
            throw new InvalidScheduleOrderException();
        }

        Set<Long> requestedScheduleIds = Set.copyOf(scheduleIds);
        Set<Long> activeScheduleIds = scheduleRepository
                .findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(userId, date)
                .stream()
                .map(Schedule::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!activeScheduleIds.equals(requestedScheduleIds)) {
            throw new InvalidScheduleOrderException();
        }

        Map<Long, Schedule> schedulesById = requestedSchedules.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Schedule::getId, schedule -> schedule));
        for (int index = 0; index < scheduleIds.size(); index++) {
            schedulesById.get(scheduleIds.get(index)).changeDisplayOrder(index);
        }
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidSchedulePeriodException();
        }
    }

    private void validateSyncRequest(LocalDate from, LocalDate to, List<CalendarSyncEvent> events) {
        if (from.isAfter(to) || events.stream().anyMatch(event -> event.date().isBefore(from) || event.date().isAfter(to))) {
            throw new InvalidCalendarSyncException();
        }
    }

    private Map<LocalDate, Long> nextDisplayOrders(List<Schedule> schedules) {
        Map<LocalDate, Long> next = new HashMap<>();
        for (Schedule schedule : schedules) {
            next.merge(schedule.getScheduleDate(), schedule.getDisplayOrder() + 1L, Math::max);
        }
        return next;
    }
}
