package com.momentory.retrospect.domain;

import com.momentory.retrospect.domain.Emotion;

/**
 * 진입 시 입력한 오늘의 일정 하나 — 값 객체.
 *
 * @param name    일정 이름 (예: "면접 스터디")
 * @param emotion 이 일정에 연결된 감정
 */
public record ScheduleItem(String name, Emotion emotion) {
}
