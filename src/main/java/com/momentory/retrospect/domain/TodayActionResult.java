package com.momentory.retrospect.domain;

/** 오늘의 작은 일에 대한 밤 회고 답 — 편지의 {@code letter_actions.result} 값과 같다. */
public enum TodayActionResult {
    /** 해 봤어요 (쉼터에서 이미 체크한 사람의 답도 여기다) */
    DONE,
    /** 못 했어요 */
    NOT_DONE,
    /** 다른 얘기 할래요 */
    SKIPPED
}
