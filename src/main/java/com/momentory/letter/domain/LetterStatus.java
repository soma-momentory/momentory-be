package com.momentory.letter.domain;

/**
 * 편지 한 통의 생성 상태. 밖(API)에는 다 만든 세 상태(READY · FALLBACK · QUIET)만 나간다.
 *
 * <pre>
 * PENDING ──▶ GENERATING ──▶ READY | FALLBACK | QUIET
 *                  │
 *                  └──▶ FAILED ──▶ GENERATING (열 때 보충)
 * </pre>
 */
public enum LetterStatus {

    /** 회고를 마친 트랜잭션이 남긴 할 일 — 아직 만들지 않았다. */
    PENDING,
    /** 누군가(뒤에서 · 열 때) 만들고 있다. 조건부 갱신으로 한쪽만 들어온다. */
    GENERATING,
    /** AI 가 쓴 편지. */
    READY,
    /** 뒤에서 만들기가 실패했다. 열 때 다시 만든다. */
    FAILED,
    /** AI 가 끝내 실패해 고정 문구로 채운 기본 편지. */
    FALLBACK,
    /** 회고 중 위기 안내가 나온 날의 조용한 편지 — 생성 모델을 부르지 않는다. */
    QUIET;

    /** 다 만들어져 그대로 보여 줄 수 있는 상태인가. */
    public boolean isDone() {
        return this == READY || this == FALLBACK || this == QUIET;
    }
}
