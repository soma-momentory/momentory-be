package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;

/**
 * Gemini {@code generationConfig.responseSchema} 로 넘길 응답 스키마.
 *
 * <p>원본은 Spring AI 가 record 로부터 스키마를 자동 생성했다. RestClient 직접 호출로 바꾸면서
 * 그 스키마를 손으로 만든다(OpenAPI 서브셋, 타입명 대문자). 필드가 record 컴포넌트명과 맞아야
 * Jackson 이 {@code parts[0].text} JSON 을 record 로 역직렬화한다.
 */
final class GeminiResponseSchemas {

    private static final Map<Class<?>, Map<String, Object>> BY_TYPE = Map.of(
            UnderstandingCheck.class, understandingSchema(),
            TurnScript.class, turnSchema(),
            DiaryOutput.class, diarySchema());

    private GeminiResponseSchemas() {
    }

    /** 해당 타입의 응답 스키마 — 없으면 null(스키마 없이 JSON 모드로만 요청). */
    static Map<String, Object> forType(Class<?> type) {
        return BY_TYPE.get(type);
    }

    private static Map<String, Object> understandingSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("reflection", str());
        props.put("situation", str());
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        props.put("offTopic", bool());
        props.put("vague", bool());
        props.put("userAsked", bool());
        return object(props, List.of("reflection", "situation", "safetyLevel"));
    }

    private static Map<String, Object> turnSchema() {
        LinkedHashMap<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("label", str());
        optionProps.put("description", str());
        Map<String, Object> optionObject = object(optionProps, List.of("label"));

        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("message", str());
        props.put("options", arrayOf(optionObject));
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        props.put("offTopic", bool());
        props.put("vague", bool());
        props.put("userAsked", bool());
        return object(props, List.of("message"));
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
