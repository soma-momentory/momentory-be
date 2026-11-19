package com.momentory.diary.domain;

import java.util.LinkedHashSet;
import java.util.List;

import com.momentory.retrospect.domain.Emotion;

/** 사건별 감정과 사건에 연결되지 않은 감정. 원본 추출 결과와 독립된 사용자 선택이다. */
public record DiaryEmotionSelection(List<Event> events, List<Emotion> unlinkedEmotions) {
    public DiaryEmotionSelection {
        events = List.copyOf(events);
        unlinkedEmotions = unlinkedEmotions.stream().distinct().toList();
        if (events.stream().map(Event::eventId).distinct().count() != events.size()) {
            throw new IllegalArgumentException("duplicate event id");
        }
    }

    public record Event(int eventId, String label, String summary, List<Emotion> emotions) {
        public Event {
            if (eventId < 1 || label == null || label.isBlank() || summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("invalid diary event");
            }
            emotions = emotions.stream().distinct().toList();
        }
    }

    public List<Emotion> allEmotions() {
        LinkedHashSet<Emotion> all = new LinkedHashSet<>();
        events.forEach(event -> all.addAll(event.emotions()));
        all.addAll(unlinkedEmotions);
        return List.copyOf(all);
    }

    /** 최초 감정 중 하나라도 현재 일기 전체에서 빠졌는지. 사건 연결·순서·추가 감정은 비교하지 않는다. */
    public boolean removesFrom(DiaryEmotionSelection original) {
        return !allEmotions().containsAll(original.allEmotions());
    }
}
