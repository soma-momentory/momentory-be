package com.momentory.letter.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.momentory.letter.domain.LetterActionSource;
import com.momentory.letter.domain.LetterStatus;

/** 화면에 보여 줄 편지 한 통 — 다 만든 상태(READY · FALLBACK · QUIET)만 담긴다. */
public record LetterView(Long id, LocalDate date, LetterStatus status, String quote, String insight,
        String tonightQuestion, List<Action> actions, List<String> helplines, boolean isNew,
        Instant openedAt) {

    public LetterView {
        actions = List.copyOf(actions);
        helplines = List.copyOf(helplines);
    }

    public record Action(int position, String title, LetterActionSource source, boolean chosen,
            String whenLabel, Long scheduleId, Instant remindAt) {
    }

    /** 넘겨 보기 목록의 한 칸 — 내용 없이 날짜만 준다(목록을 받을 때 편지를 다 만들지 않으려고). */
    public record Summary(Long id, LocalDate date, boolean isNew, Instant openedAt) {
    }
}
