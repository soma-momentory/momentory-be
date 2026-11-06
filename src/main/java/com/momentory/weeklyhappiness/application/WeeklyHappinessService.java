package com.momentory.weeklyhappiness.application;

import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessEntry;
import com.momentory.weeklyhappiness.infrastructure.WeeklyHappinessEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
public class WeeklyHappinessService {

    private final WeeklyHappinessEntryRepository repository;
    private final UserRepository userRepository;

    public WeeklyHappinessService(WeeklyHappinessEntryRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** 그 주의 기록장 — 적은 칸이 없으면 빈 기록장이다. */
    @Transactional(readOnly = true)
    public WeeklyHappinessWeek getWeek(Long userId, LocalDate weekStart) {
        requireUser(userId);
        requireOpenWeek(weekStart);
        return read(userId, weekStart);
    }

    /**
     * 그 주의 기록장을 통째로 바꾼다 — 보낸 칸은 적거나 고치고, 보내지 않았거나 비운 칸은 지운다.
     * 같은 내용을 다시 보내도 결과가 같다.
     */
    @Transactional
    public WeeklyHappinessWeek replaceWeek(Long userId, LocalDate weekStart, Map<WeeklyHappinessCategory, String> entries) {
        requireUser(userId);
        requireOpenWeek(weekStart);
        repository.lockWeek(userId, weekStart);
        Set<WeeklyHappinessCategory> cleared = EnumSet.allOf(WeeklyHappinessCategory.class);
        entries.forEach((category, content) -> {
            if (content == null || content.isBlank()) {
                return;
            }
            cleared.remove(category);
            repository.upsert(userId, weekStart, category.name(), content.strip());
        });
        if (!cleared.isEmpty()) {
            repository.deleteCategories(userId, weekStart, cleared);
        }
        return read(userId, weekStart);
    }

    private WeeklyHappinessWeek read(Long userId, LocalDate weekStart) {
        Map<WeeklyHappinessCategory, String> entries = new EnumMap<>(WeeklyHappinessCategory.class);
        repository.findByUserIdAndWeekStart(userId, weekStart)
                .forEach(entry -> entries.put(entry.getCategory(), entry.getContent()));
        return new WeeklyHappinessWeek(weekStart, entries, repository.findFirstWeekStart(userId));
    }

    /** 주의 첫날(일요일)이고, 지금이 속한 주(일요일 04:00 KST 경계) 이후가 아니어야 한다. */
    private static void requireOpenWeek(LocalDate weekStart) {
        if (!WeeklyHappinessEntry.isWeekStart(weekStart)) {
            throw WeeklyHappinessException.invalidWeekStart();
        }
        if (weekStart.isAfter(WeeklyHappinessEntry.thisWeekStart())) {
            throw WeeklyHappinessException.futureWeek();
        }
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
