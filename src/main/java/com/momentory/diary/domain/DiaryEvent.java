package com.momentory.diary.domain;

import java.util.List;
import java.util.Objects;

import com.momentory.retrospect.domain.Emotion;

/** 직접 쓴 일기에서 추출한 사건. 사진 감정과 별개로 본문에 근거한 감정만 연결한다. */
public record DiaryEvent(String label, String summary, List<Emotion> emotions) {
    public DiaryEvent {
        label = Objects.requireNonNull(label).strip();
        summary = Objects.requireNonNull(summary).strip();
        if (label.isEmpty() || summary.isEmpty()) {
            throw new IllegalArgumentException("event label and summary must not be blank");
        }
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
    }
}
