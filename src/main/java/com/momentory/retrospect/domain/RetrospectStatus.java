package com.momentory.retrospect.domain;

/**
 * 회고 세션의 수명주기 상태 — DB 조회·필터용(진행 상태 자체는 {@link Phase} 가 가진다).
 * 여러 진행 phase 를 하나로 접어 목록 조회를 단순하게 한다.
 */
public enum RetrospectStatus {

    /** 아직 진행 중 (intro·방향·스크립트 등). */
    IN_PROGRESS,
    /** 정상 종료 — 행동 카드·일기까지 마침. */
    COMPLETED,
    /** 안전 사유로 중단됨. */
    ENDED;

    public static RetrospectStatus from(Phase phase) {
        return switch (phase) {
            case COMPLETE -> COMPLETED;
            case ENDED -> ENDED;
            default -> IN_PROGRESS;
        };
    }
}
