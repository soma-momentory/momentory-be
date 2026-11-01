package com.momentory.diary.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.DiaryEvent;
import com.momentory.diary.infrastructure.DiaryEventCodec;
import com.momentory.retrospect.application.AlreadyRetrospectedTodayException;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;

/** AI 대화 없이 본문에서 사건만 추출한다. 모델 호출은 DB 트랜잭션·사용자 잠금 밖에서 한다. */
@Service
public class WrittenDiaryService {
    private final DiaryQueryService queries;
    private final DiaryService diaries;
    private final EmotionExtractor extractor;
    private final DiaryEventCodec codec;

    public WrittenDiaryService(DiaryQueryService queries, DiaryService diaries,
            EmotionExtractor extractor, DiaryEventCodec codec) {
        this.queries = queries;
        this.diaries = diaries;
        this.extractor = extractor;
        this.codec = codec;
    }

    public DiaryView write(Long userId, String original, Emotion photoEmotion) {
        if (queries.hasDiaryOn(userId, DayBoundary.today())) {
            throw new AlreadyRetrospectedTodayException();
        }
        String events = extract(original);
        return diaries.write(userId, original, photoEmotion, events);
    }

    public DiaryView update(Long userId, Long id, String original) {
        DiaryView current = queries.getOne(userId, id);
        String events = current.retrospectId() == null ? extract(original) : null;
        return diaries.updateOriginal(userId, id, original, events);
    }

    private String extract(String original) {
        // 기존 G1 추출기는 사용자 발화가 있는 상태를 입력으로 받는다. DB 회고 세션은 만들지 않는다.
        RetrospectState source = new RetrospectState("diary-" + UUID.randomUUID());
        source.addUserMessage(original);
        EmotionExtraction extraction = extractor.extract(source);
        List<ExtractedEvent> grounded = extraction.events().stream()
                .filter(event -> event.summary() != null && event.topicLabel() != null)
                .filter(event -> event.evidence().contains(1))
                .limit(RetrospectState.MAX_EVENTS).toList();
        return codec.serialize(grounded.stream().map(event -> new DiaryEvent(event.topicLabel(),
                event.summary(), emotionsOf(extraction, event.id(), original))).toList());
    }

    private static List<Emotion> emotionsOf(EmotionExtraction extraction, int eventId, String original) {
        LinkedHashSet<Emotion> result = new LinkedHashSet<>();
        for (var emotion : extraction.emotions()) {
            if (emotion.normalized() == null || !emotion.evidenceIds().contains(1)
                    || emotion.evidence() == null || emotion.evidence().isBlank() || !original.contains(emotion.evidence())) continue;
            if (emotion.eventId() != null && emotion.eventId() == eventId
                    || extraction.events().size() == 1 && emotion.eventId() == null) {
                result.add(emotion.normalized());
            }
        }
        return List.copyOf(result);
    }
}
