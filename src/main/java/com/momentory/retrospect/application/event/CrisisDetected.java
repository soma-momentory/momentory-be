package com.momentory.retrospect.application.event;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 안전 레벨이 올라갔다.
 *
 * <p>안전 감지는 회고 흐름과 직교하는 횡단 관심사다 — 로깅·감사·(추후) 알림이 여기 붙는다.
 * 흐름 제어는 이벤트가 아니라 상태 머신이 직접 한다: 이 이벤트를 받는 쪽이 회고를 중단시키지 않는다.
 *
 * @param level 병합 후 올라간 레벨
 */
public record CrisisDetected(
        String sessionId,
        SafetyLevel level,
        List<String> flags,
        String messageId) {

    public CrisisDetected {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
