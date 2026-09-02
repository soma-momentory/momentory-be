package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiActions;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiExtraction;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/**
 * Gemini {@code generationConfig.responseSchema} 로 넘길 응답 스키마 (v2 구조화 출력).
 *
 * <p>필드가 대상 record 컴포넌트명과 맞아야 Jackson 이 {@code parts[0].text} JSON 을 역직렬화한다.
 */
final class GeminiResponseSchemas {

    private static final Map<Class<?>, Map<String, Object>> BY_TYPE = Map.of(
            DiaryTurn.class, diaryTurnSchema(),
            GeminiExtraction.class, extractionSchema(),
            GeminiNeeds.class, needsSchema(),
            GeminiActions.class, actionsSchema(),
            DiaryOutput.class, diarySchema());

    private GeminiResponseSchemas() {
    }

    /** 해당 타입의 응답 스키마 — 없으면 null(스키마 없이 JSON 모드로만 요청). */
    static Map<String, Object> forType(Class<?> type) {
        return BY_TYPE.get(type);
    }

    private static Map<String, Object> diaryTurnSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("event", str());
        props.put("secondaryEvents", arrayOf(str()));
        props.put("meaning", str());
        props.put("emotionPresent", bool());
        props.put("question", str());
        props.put("empathy", str());
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        props.put("offTopic", bool());
        props.put("vague", bool());
        return object(props, List.of("question"));
    }

    /**
     * 사건(≤2) + 감정 (모델 비교 계획 §3.1).
     *
     * <p>사건의 {@code evidence} 는 required 다 — 근거 없이 뽑힌 사건은 채점에서 매칭에 실패해 FP 로
     * 잡히는데, 이는 환각 사건에 대한 의도된 페널티다(계획 §7.1).
     */
    private static Map<String, Object> extractionSchema() {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("id", integer());
        event.put("label", str());
        event.put("summary", str());
        event.put("evidence", arrayOf(integer()));

        LinkedHashMap<String, Object> emotion = new LinkedHashMap<>();
        emotion.put("eventId", integer());
        emotion.put("raw", str());
        emotion.put("normalized", str());
        emotion.put("intensity", integer());
        emotion.put("phase", str());
        emotion.put("evidence", str());
        emotion.put("evidenceIds", arrayOf(integer()));

        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("events",
                arrayOf(object(event, List.of("id", "label", "summary", "evidence"))));
        props.put("emotions", arrayOf(object(emotion, List.of("raw"))));
        return object(props, List.of());
    }

    private static Map<String, Object> needsSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("words", arrayOf(str()));
        return object(props, List.of());
    }

    private static Map<String, Object> actionsSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("actions", arrayOf(str()));
        return object(props, List.of());
    }

    private static Map<String, Object> diarySchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("diary", str());
        props.put("reframedDiary", str());
        return object(props, List.of("diary"));
    }

    private static Map<String, Object> object(LinkedHashMap<String, Object> properties,
            List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", properties);
        schema.put("propertyOrdering", List.copyOf(properties.keySet()));
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "ARRAY", "items", items);
    }

    private static Map<String, Object> str() {
        return Map.of("type", "STRING");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "BOOLEAN");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "INTEGER");
    }
}
