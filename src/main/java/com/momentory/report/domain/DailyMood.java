package com.momentory.report.domain;

import java.time.LocalDate;

import com.momentory.retrospect.domain.Emotion;

/**
 * 한 주의 마음 일곱 칸 중 하루 — 그날(KST) 일기에 남은 현재 감정이다.
 *
 * <p>일기를 남기지 않은 날은 {@code emotion} 이 null 이다(리포트는 그 날도 빈 칸으로 보여준다).
 */
public record DailyMood(LocalDate date, Emotion emotion) {

    public boolean isRecorded() {
        return emotion != null;
    }
}
