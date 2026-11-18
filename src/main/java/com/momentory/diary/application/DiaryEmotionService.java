package com.momentory.diary.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.diary.domain.Diary;
import com.momentory.diary.domain.DiaryEmotionSelection;
import com.momentory.diary.domain.DiaryEmotionSelection.Event;
import com.momentory.diary.infrastructure.DiaryEventCodec;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;

/** 원본 추출은 그대로 두고 사건별 사용자 선택만 일기에 저장한다. */
@Service
public class DiaryEmotionService {
    private final DiaryRepository diaries;
    private final DiaryEventCodec codec;
    private final RetrospectRepository retrospects;
    private final RetrospectStateCodec states;

    public DiaryEmotionService(DiaryRepository diaries, DiaryEventCodec codec,
            RetrospectRepository retrospects, RetrospectStateCodec states) {
        this.diaries = diaries;
        this.codec = codec;
        this.retrospects = retrospects;
        this.states = states;
    }

    public record EventSelection(int eventId, List<Emotion> emotions) {
        public EventSelection { emotions = List.copyOf(emotions); }
    }

    @Transactional
    public DiaryView update(Long userId, Long id, List<EventSelection> events, List<Emotion> unlinked) {
        Diary diary = diaries.findForEmotionUpdate(id, userId).orElseThrow(DiaryNotFoundException::new);
        DiaryEmotionSelection original = originalOf(diary, sourceOf(diary));
        DiaryEmotionSelection current = diary.getEmotionSelectionJson() == null ? original
                : codec.deserializeSelection(diary.getEmotionSelectionJson());
        Map<Integer, EventSelection> byId;
        try {
            byId = events.stream().collect(Collectors.toMap(EventSelection::eventId, Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw new InvalidDiaryEmotionsException();
        }
        if (byId.size() != current.events().size()
                || !byId.keySet().equals(current.events().stream().map(Event::eventId).collect(Collectors.toSet()))) {
            throw new InvalidDiaryEmotionsException();
        }
        DiaryEmotionSelection selected = new DiaryEmotionSelection(current.events().stream()
                .map(event -> new Event(event.eventId(), event.label(), event.summary(), byId.get(event.eventId()).emotions()))
                .toList(), unlinked);
        diary.updateEmotions(original, codec.serializeSelection(original), selected, codec.serializeSelection(selected));
        return DiaryView.from(diary, selected);
    }

    /** 목록의 옛 회고도 한 번에 읽는다. 일기마다 회고를 조회하는 N+1을 만들지 않는다. */
    public List<DiaryView> viewsOf(List<Diary> list) {
        List<Long> ids = list.stream().filter(d -> d.getEmotionSelectionJson() == null && d.getOriginalEmotionSelectionJson() == null)
                .map(Diary::getRetrospectId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Retrospect> sources = ids.isEmpty() ? Map.of() : retrospects.findAllById(ids).stream()
                .collect(Collectors.toMap(Retrospect::getId, Function.identity()));
        return list.stream().map(diary -> viewOf(diary, diary.getRetrospectId() == null ? null : sources.get(diary.getRetrospectId()))).toList();
    }

    public DiaryView viewOf(Diary diary) {
        return viewOf(diary, diary.getEmotionSelectionJson() == null && diary.getOriginalEmotionSelectionJson() == null
                ? sourceOf(diary) : null);
    }

    private DiaryView viewOf(Diary diary, Retrospect source) {
        DiaryEmotionSelection selected = diary.getEmotionSelectionJson() == null ? originalOf(diary, source)
                : codec.deserializeSelection(diary.getEmotionSelectionJson());
        return DiaryView.from(diary, selected);
    }

    private Retrospect sourceOf(Diary diary) {
        return diary.getRetrospectId() == null ? null
                : retrospects.findByIdAndUserId(diary.getRetrospectId(), diary.getUserId()).orElse(null);
    }

    private DiaryEmotionSelection originalOf(Diary diary, Retrospect source) {
        if (diary.getOriginalEmotionSelectionJson() != null) {
            return codec.deserializeSelection(diary.getOriginalEmotionSelectionJson());
        }
        List<Event> events = new ArrayList<>();
        LinkedHashSet<Emotion> explicitUnlinked = new LinkedHashSet<>();
        if (diary.getRetrospectId() == null) {
            var written = codec.deserialize(diary.getEventsJson());
            for (int i = 0; i < written.size(); i++) {
                var event = written.get(i);
                // 직접 선택한 일기 감정만 보여준다. 사건 추출기가 추론한 감정을 새로 추가하지 않는다.
                List<Emotion> selected = event.emotions().stream().filter(emotion -> tagsOf(diary).contains(emotion)).toList();
                events.add(new Event(i + 1, event.label(), event.summary(), selected));
            }
        } else if (source != null && source.getUserId().equals(diary.getUserId())) {
            var state = states.diaryEmotions(source.getStateJson());
            for (var event : state.events()) {
                if (event.topicLabel() == null) continue;
                List<Emotion> emotions = state.emotions().stream()
                        .filter(emotion -> emotion.normalized() != null)
                        .filter(emotion -> emotion.eventId() != null && emotion.eventId() == event.id()
                                || state.events().size() == 1 && emotion.eventId() == null)
                        .map(emotion -> emotion.normalized()).distinct().toList();
                LinkedHashSet<Emotion> linked = new LinkedHashSet<>();
                if (Integer.valueOf(event.id()).equals(state.selectedEventId())) linked.addAll(state.confirmedEmotions());
                linked.addAll(emotions);
                events.add(new Event(event.id(), event.topicLabel(), event.summary() == null ? event.topicLabel() : event.summary(), List.copyOf(linked)));
            }
            for (var emotion : state.emotions()) {
                if (emotion.normalized() == null) continue;
                boolean linked = emotion.eventId() == null
                        ? state.events().size() == 1 && !events.isEmpty()
                        : events.stream().anyMatch(event -> event.eventId() == emotion.eventId());
                if (!linked) explicitUnlinked.add(emotion.normalized());
            }
        }
        LinkedHashSet<Emotion> unlinked = new LinkedHashSet<>(tagsOf(diary));
        events.forEach(event -> unlinked.removeAll(event.emotions()));
        unlinked.addAll(explicitUnlinked);
        return new DiaryEmotionSelection(events, List.copyOf(unlinked));
    }

    private List<Emotion> tagsOf(Diary diary) {
        LinkedHashSet<Emotion> tags = new LinkedHashSet<>();
        if (diary.getPrimaryEmotion() != null) tags.add(diary.getPrimaryEmotion());
        DiaryView.splitCsv(diary.getEmotions()).forEach(key -> Emotion.fromKey(key).ifPresent(tags::add));
        return List.copyOf(tags);
    }
}
