package com.momentory.diary.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.momentory.diary.domain.DiaryEvent;
import com.momentory.retrospect.infrastructure.persistence.EmotionJsonModule;
import tools.jackson.databind.ObjectMapper;

/**
 * 회고 상태 codec과 같은 경계. 본문·추출 결과를 로그에 남기지 않는다.
 * 옛 10종 감정 이름으로 저장된 사건도 읽는다({@link EmotionJsonModule}).
 */
@Component
public class DiaryEventCodec {
    private final ObjectMapper mapper;

    public DiaryEventCodec(ObjectMapper mapper) {
        this.mapper = EmotionJsonModule.applyTo(mapper);
    }

    public record Snapshot(List<DiaryEvent> events) {
        public Snapshot {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public String serialize(List<DiaryEvent> events) {
        return mapper.writeValueAsString(new Snapshot(events));
    }

    public List<DiaryEvent> deserialize(String json) {
        return json == null ? List.of() : mapper.readValue(json, Snapshot.class).events();
    }
}
