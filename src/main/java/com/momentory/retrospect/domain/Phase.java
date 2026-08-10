package com.momentory.retrospect.domain;

/**
 * 회고 흐름의 메인 축 (채팅 최적 시나리오 기준).
 *
 * <pre>
 * (await_schedule) → intro → await_direction → (await_sub_direction) → script → complete
 *                                                                             ↘ ended (안전 중단)
 * </pre>
 */
public enum Phase {

    /** 일정이 여러 개라 어떤 일정을 이야기할지 선택을 기다린다. */
    AWAIT_SCHEDULE("await_schedule"),
    /** 1턴(구체적인 순간) 질문이 나갔고 첫 답변을 기다린다. */
    INTRO("intro"),
    /** 이해 확인이 나갔고 회고 방향 4택을 기다린다. */
    AWAIT_DIRECTION("await_direction"),
    /** "다른 관점" 세부 2택(인지 재구성/강점)을 기다린다. */
    AWAIT_SUB_DIRECTION("await_sub_direction"),
    /** 모드 스크립트 진행 중. */
    SCRIPT("script"),
    /** 정상 종료 — 일기(·행동 카드)까지 나갔다. */
    COMPLETE("complete"),
    /** 안전 중단. */
    ENDED("ended");

    private final String key;

    Phase(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean isTerminal() {
        return this == COMPLETE || this == ENDED;
    }
}
