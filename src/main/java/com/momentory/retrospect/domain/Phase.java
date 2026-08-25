package com.momentory.retrospect.domain;

/**
 * 회고 흐름의 메인 축 (채팅 최적 시나리오 기준).
 *
 * <pre>
 * (await_schedule) → intro → await_direction → (await_sub_direction) → script → complete
 *                                                                             ↘ safety_hold ⇄ (이어가기)
 *                                                                             ↘ ended (어뷰징 종료)
 * </pre>
 *
 * <p>{@code safety_hold} 는 위기 발화에 안내를 띄우고 <b>멈추되 끝내지는 않는</b> 상태다 —
 * 사용자가 「이어서 얘기하기」를 고르면 중단됐던 phase 로 되돌아간다. 종결이 아니라서
 * {@link #isTerminal} 이 false 다. 어뷰징 캡 종료만 {@code ended}(종결)로 남는다.
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
    /** 위기 발화 안내 후 멈춤 — 「이어서 얘기하기」로 되돌릴 수 있다(종결 아님). */
    SAFETY_HOLD("safety_hold"),
    /** 정상 종료 — 일기(·행동 카드)까지 나갔다. */
    COMPLETE("complete"),
    /** 어뷰징 캡 도달로 종료. */
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
