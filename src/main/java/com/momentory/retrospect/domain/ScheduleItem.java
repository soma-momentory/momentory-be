package com.momentory.retrospect.domain;

import com.momentory.retrospect.domain.Emotion;

/**
 * 진입 시 입력한 오늘의 일정 하나 — 값 객체.
 *
 * @param id        schedules 테이블의 일정 id. 자유 입력·목록 밖 일정이면 {@code null}.
 * @param name      일정 이름 (예: "면접 스터디")
 * @param emotion   이 일정에 연결된 감정
 * @param completed 오늘 이 일정을 마쳤는가. 대화 소재를 고를 때 <b>끝난 일을 먼저</b> 본다 —
 *                  아직 안 한 일은 돌아볼 거리가 없다. 모르면 {@code false}.
 */
public record ScheduleItem(Long id, String name, Emotion emotion, boolean completed) {

    /** id·완료 여부를 신경 쓰지 않는 자리(자유 입력, 테스트)용 편의 생성자. */
    public ScheduleItem(String name, Emotion emotion) {
        this(null, name, emotion, false);
    }
}
