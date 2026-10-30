package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
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
            DiaryOutput.class, diarySchema());

    private GeminiResponseSchemas() {
    }

    /** 해당 타입의 응답 스키마 — 없으면 null(스키마 없이 JSON 모드로만 요청). */
    static Map<String, Object> forType(Class<?> type) {
        return BY_TYPE.get(type);
    }

    private static Map<String, Object> diaryTurnSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("eventPending", Map.of("type", "BOOLEAN", "description",
                "답변 전체가 아직 전인 일정만 가리킬 때 true. 실제로 한 경험도 포함되면 false."));
        props.put("emotionPresent", Map.of("type", "BOOLEAN", "description",
                "이번 답변에서 감정·기분·의욕 같은 주관적 상태를 표현했으면 true."));
        props.put("emotionCausePresent", Map.of("type", "BOOLEAN", "description",
                "그 감정이 생긴 이유를 이번 답변에서 설명했을 때만 true. 감정만 답하면 false."));
        props.put("event", str());
        props.put("secondaryEvents", arrayOf(str()));
        props.put("meaning", str());
        props.put("question", Map.of("type", "STRING", "description",
                "미래 일정뿐이면 그전 경험 질문. 그 외 감정 없음=기분 질문, 감정만 있음=계기 질문, 감정과 이유 있음=빈 문자열."));
        props.put("empathy", str());
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        props.put("noMoreToAsk", bool());
        props.put("offTopic", bool());
        props.put("vague", bool());
        // primitive boolean은 null로 역직렬화할 수 없으므로 생략 가능한 필드로 두지 않는다.
        return object(props, List.of("question", "empathy", "emotionPresent", "noMoreToAsk",
                "offTopic", "vague", "eventPending", "emotionCausePresent"));
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
        props.put("inferredEmotion", str());
        return object(props, List.of());
    }

    private static Map<String, Object> needsSchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("words", arrayOf(str()));
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
