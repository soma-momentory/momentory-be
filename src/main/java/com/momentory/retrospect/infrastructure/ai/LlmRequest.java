package com.momentory.retrospect.infrastructure.ai;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * LLM 호출 한 건의 입력 — 값 객체.
 *
 * <p>{@code sessionId} 와 {@code phase} 는 모델에 안 보낸다. 계측용이다 —
 * 어느 세션의 어느 phase 에서 토큰이 나갔는지 봐야 비용 우선순위를 정할 수 있다.
 */
public record LlmRequest(
        String sessionId,
        LlmRole role,
        String phase,
        String system,
        String user) {

    public static LlmRequest of(RetrospectState state, LlmRole role, String system, String user) {
        return new LlmRequest(state.id(), role, state.phase().key(), system, user);
    }
}
