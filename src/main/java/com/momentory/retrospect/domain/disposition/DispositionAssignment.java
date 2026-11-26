package com.momentory.retrospect.domain.disposition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * 사용자를 실험 집단에 넣는 규칙 — 순수 계산이고 같은 사용자는 늘 같은 집단이다.
 *
 * @param mode            {@link Mode}
 * @param userIds         {@code LISTED} 일 때 반영을 켤 사용자 id
 * @param excludedUserIds 어느 모드에서든 실험에서 빼는 사용자 id(개발자 계정 등) — 집단 없이 기본값에서 시작한다
 */
public record DispositionAssignment(Mode mode, Set<Long> userIds, Set<Long> excludedUserIds) {

    /** 배정을 가르는 실험 이름. 바꾸면 사용자의 집단이 다시 섞이므로 실험 중에는 고정한다. */
    private static final String EXPERIMENT = "disposition-apply-v1";

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
        excludedUserIds = excludedUserIds == null ? Set.of() : Set.copyOf(excludedUserIds);
    }

    public DispositionArm armOf(long userId) {
        if (excludedUserIds.contains(userId)) {
            return DispositionArm.NONE;
        }
        return switch (mode) {
            case OFF -> DispositionArm.NONE;
            case LISTED -> userIds.contains(userId) ? DispositionArm.TREATMENT : DispositionArm.NONE;
            case RANDOM -> firstHashBit(userId) == 0 ? DispositionArm.TREATMENT : DispositionArm.CONTROL;
        };
    }

    /** 가입 순서와 얽히지 않도록 실험 이름과 id 를 SHA-256 으로 섞은 값의 첫 비트. */
    private static int firstHashBit(long userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((EXPERIMENT + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return hash[0] & 1;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
