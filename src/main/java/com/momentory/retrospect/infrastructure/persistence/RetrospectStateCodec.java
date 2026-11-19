package com.momentory.retrospect.infrastructure.persistence;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStateSnapshot;

import tools.jackson.databind.ObjectMapper;

/**
 * 회고 세션 상태 ↔ JSON 문자열 변환. 도메인은 Jackson 을 모른 채로 두고, 직렬화는 순수
 * 스냅샷 record 를 대상으로 여기서만 한다. 옛 10종 감정 이름으로 저장된 스냅샷도 읽는다
 * ({@link EmotionJsonModule}).
 */
@Component
public class RetrospectStateCodec {

    private final ObjectMapper objectMapper;

    public RetrospectStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = EmotionJsonModule.applyTo(objectMapper);
    }

    public String serialize(RetrospectState state) {
        return objectMapper.writeValueAsString(state.toSnapshot());
    }

    public RetrospectState deserialize(String json) {
        RetrospectStateSnapshot snapshot = objectMapper.readValue(json, RetrospectStateSnapshot.class);
        // 추가된 nullable 진행 필드의 구버전 기본값과 탐색 사건 복원은 도메인이 처리한다.
        return RetrospectState.fromSnapshot(snapshot);
    }

    public record DiaryEmotionsSource(java.util.List<com.momentory.retrospect.domain.ExtractedEvent> events,
            java.util.List<com.momentory.retrospect.domain.ExtractedEmotion> emotions,
            Integer selectedEventId, java.util.List<com.momentory.retrospect.domain.Emotion> confirmedEmotions) {}

    /** 일기 조회는 옛 세션의 진행 필드가 없어도 저장된 사건·감정만 읽는다. 진행 상태를 재구성하지 않는다. */
    public DiaryEmotionsSource diaryEmotions(String json) {
        var root = objectMapper.readTree(json);
        java.util.List<com.momentory.retrospect.domain.ExtractedEvent> events = root.path("events").isArray()
                ? objectMapper.convertValue(root.path("events"), objectMapper.getTypeFactory().constructCollectionType(
                        java.util.List.class, com.momentory.retrospect.domain.ExtractedEvent.class)) : java.util.List.of();
        java.util.List<com.momentory.retrospect.domain.ExtractedEmotion> emotions = root.path("emotions").isArray()
                ? objectMapper.convertValue(root.path("emotions"), objectMapper.getTypeFactory().constructCollectionType(
                        java.util.List.class, com.momentory.retrospect.domain.ExtractedEmotion.class)) : java.util.List.of();
        java.util.List<com.momentory.retrospect.domain.Emotion> confirmed = root.path("confirmedEmotions").isArray()
                ? objectMapper.convertValue(root.path("confirmedEmotions"), objectMapper.getTypeFactory().constructCollectionType(
                        java.util.List.class, com.momentory.retrospect.domain.Emotion.class)) : java.util.List.of();
        Integer selected = root.path("selectedEventId").isInt() ? root.path("selectedEventId").intValue() : null;
        if (selected == null && root.path("explorationEntered").asBoolean(false)) {
            // RetrospectState.fromSnapshot와 같은 구버전 복원 규칙.
            selected = events.stream().max(java.util.Comparator.comparingInt(event -> event.evidence().size()))
                    .map(com.momentory.retrospect.domain.ExtractedEvent::id).orElse(null);
        }
        return new DiaryEmotionsSource(events, emotions, selected, confirmed);
    }
}
