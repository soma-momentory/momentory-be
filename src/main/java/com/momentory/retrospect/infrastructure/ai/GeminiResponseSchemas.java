package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiActions;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEmotions;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/**
 * Gemini {@code generationConfig.responseSchema} 로 넘길 응답 스키마 (v2 구조화 출력).
 *
 * <p>필드가 대상 record 컴포넌트명과 맞아야 Jackson 이 {@code parts[0].text} JSON 을 역직렬화한다.
 */
final class GeminiResponseSchemas {

    private static final Map<Class<?>, Map<String, Object>> BY_TYPE = Map.of(
            DiaryTurn.class, diaryTurnSchema(),
            GeminiEmotions.class, emotionsSchema(),
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
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        props.put("offTopic", bool());
        props.put("vague", bool());
        return object(props, List.of("question"));
    }

    private static Map<String, Object> emotionsSchema() {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("raw", str());
        item.put("normalized", str());
        item.put("timing", str());
        item.put("cause", str());
        item.put("evidence", str());
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("emotions", arrayOf(object(item, List.of("raw"))));
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
}
