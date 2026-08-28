package com.momentory.retrospect.application;

import java.util.List;

import com.momentory.retrospect.domain.ScheduleItem;

/**
 * 회고 시작 입력 (채팅흐름_v2) — 시작 시 감정을 고르지 않는다.
 *
 * @param schedules 오늘의 일정 0개 이상. 서버가 이 중 대화로 이어갈 개인화 소재 하나를 고른다
 *                  (비었으면 '오늘 하루' 회고). 각 일정의 감정 태그(홈에서 단 것)는 소재로만 쓴다.
 * @param nickname  호칭용. 선택.
 * @param interest  온보딩 관심분야(예: "취업"). 개인화 소재 선택·질문 구체화에 쓴다. 선택.
 */
public record StartCommand(
        List<ScheduleItem> schedules,
        String nickname,
        String interest) {

    public StartCommand {
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
    }

    /** 단일 일정 편의 생성자 (테스트용). */
    public static StartCommand single(String schedule, com.momentory.retrospect.domain.Emotion scheduleEmotion,
            String nickname) {
        return new StartCommand(List.of(new ScheduleItem(schedule, scheduleEmotion)), nickname, null);
    }

    /** 일정 없는 '오늘 하루' 회고 편의 생성자 (테스트용). */
    public static StartCommand today(String nickname, String interest) {
        return new StartCommand(List.of(), nickname, interest);
    }
}
