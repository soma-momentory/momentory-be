package com.momentory.retrospect.domain;

import java.util.List;
import java.util.Optional;

import com.momentory.retrospect.domain.Emotion;

/**
 * 일정이 여러 개일 때 1턴 질문의 대상 일정을 고르는 규칙 — AI 0회.
 *
 * <p>우선순위:
 * <ol>
 *   <li>일정이 하나면 그것.</li>
 *   <li>현재 감정과 같은 감정이 태그된 일정이 정확히 하나면 그것 — 지금 마음과 이어진 일정일
 *       가능성이 가장 높다.</li>
 *   <li>관심분야 키워드와 이름이 겹치는 일정이 정확히 하나면 그것.</li>
 *   <li>그 외(0개 또는 여러 개 매칭)는 empty — 엔진이 사용자에게 선택권을 준다.</li>
 * </ol>
 */
public final class SchedulePicker {

    private SchedulePicker() {
    }

    public static Optional<ScheduleItem> pick(List<ScheduleItem> schedules, Emotion currentEmotion,
            String interest) {
        if (schedules == null || schedules.isEmpty()) {
            return Optional.empty();
        }
        if (schedules.size() == 1) {
            return Optional.of(schedules.get(0));
        }

        List<ScheduleItem> byEmotion = schedules.stream()
                .filter(s -> s.emotion() == currentEmotion)
                .toList();
        if (byEmotion.size() == 1) {
            return Optional.of(byEmotion.get(0));
        }

        if (interest != null && !interest.isBlank()) {
            String key = normalize(interest);
            List<ScheduleItem> byInterest = schedules.stream()
                    .filter(s -> {
                        String name = normalize(s.name());
                        return name.contains(key) || key.contains(name);
                    })
                    .toList();
            if (byInterest.size() == 1) {
                return Optional.of(byInterest.get(0));
            }
        }
        return Optional.empty();
    }

    private static String normalize(String s) {
        return s.replace(" ", "").toLowerCase();
    }
}
