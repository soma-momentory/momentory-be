package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.StrategyReply;
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
            DiaryOutput.class, diarySchema(),
            DiaryDraft.class, diaryDraftSchema(),
            StrategyReply.class, strategyReplySchema(),
            ReflectionSummary.class, reflectionSummarySchema());

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

    private static Map<String, Object> reflectionSummarySchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("summary", Map.of("type", "STRING", "description",
                "사용자가 말한 느낀 점을 일기체 한 문장으로. 말하지 않은 내용은 넣지 않는다."));
        return object(props, List.of("summary"));
    }

    private static Map<String, Object> strategyReplySchema() {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        // 흐름을 먼저 판단하게 한다 — 새 이야기인지, 더 물을 것이 남았는지를 정한 뒤에 문장을 쓴다.
        props.put("newTopic", Map.of("type", "BOOLEAN", "description",
                "방금 답변이 앞에서 다루던 일과 다른 새 이야기를 꺼냈으면 true."));
        props.put("topic", Map.of("type", "STRING", "description", "지금 다루는 일의 짧은 이름."));
        props.put("nothingMoreToAsk", Map.of("type", "BOOLEAN", "description",
                "이 이야기에서 더 물을 것이 없으면 true — 이미 충분히 들었거나, 사용자가 더 말하기 어려워한다."));
        props.put("ack", Map.of("type", "STRING", "description", "방금 답변을 받아주는 말. 질문은 넣지 않는다."));
        props.put("question", Map.of("type", "STRING", "description",
                "이어서 물을 질문 한 문장. 묻지 않는 턴이거나 nothingMoreToAsk 가 true 면 빈 문자열."));
        props.put("safetyLevel", str());
        props.put("safetyFlags", arrayOf(str()));
        return object(props, List.of("newTopic", "nothingMoreToAsk", "ack", "question", "safetyLevel"));
    }

    private static Map<String, Object> diaryDraftSchema() {
        LinkedHashMap<String, Object> paragraph = new LinkedHashMap<>();
        paragraph.put("event", Map.of("type", "STRING", "description",
                "이 문단이 다루는 일 한 줄. 때나 장소가 다른 일은 서로 다른 문단이다."));
        paragraph.put("sentences", Map.of("type", "ARRAY", "items", str(), "description",
                "이 사건에 대한 문장들. 원소 하나에 한 문장만 담는다."));
        LinkedHashMap<String, Object> correction = new LinkedHashMap<>();
        correction.put("before", str());
        correction.put("after", str());
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        // 문단보다 먼저 채우게 한다 — 고쳐 말한 곳을 짚은 뒤에 일기를 쓴다.
        props.put("corrections", Map.of("type", "ARRAY", "items", object(correction, List.of("before", "after")),
                "description", "사용자가 자기 말을 고쳐 말한 곳. before=고치기 전 표현, after=고친 표현. 없으면 빈 목록."));
        props.put("paragraphs", Map.of("type", "ARRAY", "items", object(paragraph, List.of("event", "sentences")),
                "description", "문단 목록. 문단 하나가 한 사건(맥락)이다. 한 가지 일만 이야기했으면 문단은 하나다."));
        return object(props, List.of("corrections", "paragraphs"));
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
