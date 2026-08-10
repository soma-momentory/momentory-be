package com.momentory.retrospect.application;

import java.util.List;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ScheduleItem;

/**
 * 회고 시작 입력 (PDF "회고 시작 전 감정 선택" + 다중 일정 확장).
 *
 * @param schedules      오늘의 일정 1개 이상(각각 감정 태그 포함). 여러 개면
 *                       {@code SchedulePicker} 규칙으로 대상을 고르고, 못 고르면 사용자에게 묻는다.
 * @param currentEmotion 현재 감정. 필수.
 * @param nickname       호칭용. 선택.
 * @param interest       온보딩 관심분야(예: "취업"). 일정 선택 규칙에만 쓰인다. 선택.
 */
public record StartCommand(
        List<ScheduleItem> schedules,
        Emotion currentEmotion,
        String nickname,
        String interest) {

    public StartCommand {
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
    }

    /** 단일 일정 편의 생성자 (테스트·기존 경로용). */
    public static StartCommand single(String schedule, Emotion scheduleEmotion,
            Emotion currentEmotion, String nickname) {
        return new StartCommand(List.of(new ScheduleItem(schedule, scheduleEmotion)),
                currentEmotion, nickname, null);
    }
}
