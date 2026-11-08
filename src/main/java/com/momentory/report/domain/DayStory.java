package com.momentory.report.domain;

import java.time.LocalDate;
import java.util.List;

import com.momentory.retrospect.domain.Emotion;

/**
 * 일기 한 편에서 리포트가 쓰는 것 — 회고로 만든 일기든 직접 쓴 일기든 이 모양으로 모은다.
 *
 * @param emotion     대표 감정(감정 없이 끝난 일기면 null)
 * @param event       그 감정이 붙은 사건 이름(없으면 null)
 * @param quote       사용자가 직접 쓴 문장 한 줄(고를 수 없으면 null). 회고로 만든 일기의 본문은 AI 글이라
 *                    쓰지 않는다
 * @param quoteLabel  그 문장 위에 붙는 이름 — 편지에서 고른 일 또는 사건 이름(없으면 null)
 * @param fromLetter  그 문장이 편지에서 고른 일에 대한 답인가
 * @param crisis      그 회고에서 위기 안내가 나왔는가
 * @param scheduleId  회고가 고른 일정(없으면 null)
 * @param diary       일기 본문 — AI 글의 재료로만 쓰고 응답에 싣지 않는다
 * @param userWords   사용자가 한 말 — AI 글의 재료로만 쓰고 응답에 싣지 않는다
 */
public record DayStory(Long diaryId, LocalDate date, Emotion emotion, String event, String quote,
        String quoteLabel, boolean fromLetter, boolean crisis, Long scheduleId, String diary,
        List<String> userWords) {

    public DayStory {
        userWords = userWords == null ? List.of() : List.copyOf(userWords);
    }
}
