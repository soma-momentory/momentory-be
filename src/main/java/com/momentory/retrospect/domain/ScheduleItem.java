package com.momentory.retrospect.domain;

import com.momentory.retrospect.domain.Emotion;

/**
 * 진입 시 입력한 오늘의 일정 하나 — 값 객체.
 *
 * @param id      schedules 테이블의 일정 id. 자유 입력·목록 밖 일정이면 {@code null}.
 * @param name    일정 이름 (예: "면접 스터디")
 * @param emotion 이 일정에 연결된 감정
 */
public record ScheduleItem(Long id, String name, Emotion emotion) {

    /** id 없는 일정(자유 입력, 또는 id 를 신경 쓰지 않는 테스트·기존 경로)용 편의 생성자. */
    public ScheduleItem(String name, Emotion emotion) {
        this(null, name, emotion);
    }
}
