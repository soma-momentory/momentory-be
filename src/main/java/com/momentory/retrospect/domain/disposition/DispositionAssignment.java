package com.momentory.retrospect.domain.disposition;

import java.util.Set;

/**
 * 사용자를 실험 집단에 넣는 규칙 — 순수 계산이고 같은 사용자는 늘 같은 집단이다.
 *
 * @param mode    {@link Mode}
 * @param userIds {@code LISTED} 일 때 반영을 켤 사용자 id
 */
public record DispositionAssignment(Mode mode, Set<Long> userIds) {

    public enum Mode {
        /** 아무에게도 반영하지 않는다(기본값). 성향 관찰은 계속 저장한다. */
        OFF,
        /** 적어 둔 계정에만 반영한다 — 동작 확인용. */
        LISTED,
        /** 사용자 id 로 반반 나눈다 — 실험용. */
        RANDOM
    }

    public DispositionAssignment {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }

    public DispositionArm armOf(long userId) {
        return switch (mode) {
            case OFF -> DispositionArm.NONE;
            case LISTED -> userIds.contains(userId) ? DispositionArm.TREATMENT : DispositionArm.NONE;
            // 가입 순서(짝수 · 홀수)와 얽히지 않게 id 를 섞은 뒤 나눈다.
            case RANDOM -> (Long.hashCode(userId * 0x9E3779B97F4A7C15L) & 1) == 0
                    ? DispositionArm.TREATMENT : DispositionArm.CONTROL;
        };
    }
}
