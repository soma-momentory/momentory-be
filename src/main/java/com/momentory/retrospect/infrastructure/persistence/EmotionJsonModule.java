package com.momentory.retrospect.infrastructure.persistence;

import com.momentory.retrospect.domain.Emotion;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 저장된 JSON 속 감정을 읽는 규칙 — <b>옛 10종 이름으로 저장된 값을 새 12종으로 옮겨 읽는다</b>.
 *
 * <p>회고 상태 스냅샷({@code retrospects.state_json})과 일기 사건({@code diaries.events_json})은
 * 감정을 열거 상수 이름으로 담는다. 감정 체계가 10종에서 12종으로 바뀌면서 {@code HAPPY}·
 * {@code FRUSTRATED} 같은 옛 이름은 더 이상 상수가 아니므로, 기본 역직렬화로는 옛 행을 읽다가
 * 실패한다. 덩어리 JSON 을 SQL 로 고치는 대신 읽는 쪽에서 받아들인다 — 대응 규칙은 도메인
 * ({@link Emotion#fromStored(String)})이 쥐고, 도메인은 여전히 Jackson 을 모른다.
 *
 * <p>쓰기는 손대지 않는다 — 새로 저장하는 값은 언제나 새 상수 이름이다. 어느 체계에도 없는 값은
 * 조용히 비우지 않고 실패시킨다(목록 안에 null 이 섞이면 더 먼 곳에서 깨진다).
 */
public final class EmotionJsonModule {

    private EmotionJsonModule() {
    }

    /** 주어진 매퍼의 설정을 그대로 두고 감정 읽기 규칙만 얹은 매퍼를 만든다. */
    public static ObjectMapper applyTo(ObjectMapper mapper) {
        return mapper.rebuild().addModule(module()).build();
    }

    static JacksonModule module() {
        SimpleModule module = new SimpleModule("momentory-emotion");
        module.addDeserializer(Emotion.class, new LegacyAwareDeserializer());
        return module;
    }

    private static final class LegacyAwareDeserializer extends ValueDeserializer<Emotion> {

        @Override
        public Emotion deserialize(JsonParser p, DeserializationContext ctxt) {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return (Emotion) ctxt.handleUnexpectedToken(Emotion.class, p);
            }
            String value = p.getString();
            return Emotion.fromStored(value).orElseGet(() -> (Emotion) ctxt.handleWeirdStringValue(
                    Emotion.class, value, "알 수 없는 감정 값"));
        }
    }
}
