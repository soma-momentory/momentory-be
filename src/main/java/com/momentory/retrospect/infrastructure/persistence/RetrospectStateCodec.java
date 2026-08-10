package com.momentory.retrospect.infrastructure.persistence;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStateSnapshot;

import tools.jackson.databind.ObjectMapper;

/**
 * 회고 세션 상태 ↔ JSON 문자열 변환. 도메인은 Jackson 을 모른 채로 두고, 직렬화는 순수
 * 스냅샷 record 를 대상으로 여기서만 한다.
 */
@Component
public class RetrospectStateCodec {

    private final ObjectMapper objectMapper;

    public RetrospectStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(RetrospectState state) {
        return objectMapper.writeValueAsString(state.toSnapshot());
    }

    public RetrospectState deserialize(String json) {
        RetrospectStateSnapshot snapshot = objectMapper.readValue(json, RetrospectStateSnapshot.class);
        return RetrospectState.fromSnapshot(snapshot);
    }
}
