package com.momentory.report.presentation;

import java.time.LocalDate;
import java.util.List;

import com.momentory.report.domain.DailyMood;
import com.momentory.retrospect.domain.Emotion;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 「이번 주의 마음」 한 칸. 일기를 남기지 않은 날은 {@code emotion} 이 명시적으로 null 로 온다 —
 * 일곱 칸이 요일 자리에 그대로 대응해야 해서 필드를 빼지 않는다.
 *
 * <p>{@code emotion} 은 점 하나가 맡는 <b>대표 감정</b>이고, {@code emotions} 는 그날 감정
 * <b>전부</b>다(대표 감정이 맨 앞). 일기 API 의 {@code primaryEmotion} / {@code emotions} 와 같은
 * 짝이다 — 점이 하나뿐이라 하루에 감정이 둘 이상이면 화면은 눌러서 나머지를 본다.
 */
public record DailyMoodResponse(
        @Schema(description = "그 날(KST)", example = "2026-08-17") LocalDate date,
        @Schema(description = "그날의 대표 감정 키 — 기록이 없으면 null", example = "calm")
        String emotion,
        @Schema(description = "그날 감정 키 전체(대표 감정이 맨 앞)", example = "[\"depressed\", \"happy\"]")
        List<String> emotions) {

    static DailyMoodResponse from(DailyMood mood) {
        return new DailyMoodResponse(mood.date(), keyOf(mood.emotion()),
                mood.emotions().stream().map(Emotion::key).toList());
    }

    private static String keyOf(Emotion emotion) {
        return emotion == null ? null : emotion.key();
    }
}
