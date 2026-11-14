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
        // 옛 10종에서 옮겨 읽으면 두 감정이 하나로 합쳐질 수 있다(피곤함·무기력 → 지침) — 겹친 것은 하나만 둔다.
        emotions = emotions == null ? List.of() : emotions.stream().distinct().toList();
    }
}
