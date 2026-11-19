package com.momentory.letter.domain;

/** 그림일기 한 장의 상태. 실패해도 다시 만들지 않는다 — 한 번 들어간 편지는 그것으로 끝이다(비용). */
public enum LetterPictureStatus {
    /** 장면 나누기 · 그리기 중. 서버가 그사이 멈추면 이 상태로 남고 그림 없이 편지만 보인다. */
    GENERATING,
    /** 다 그렸다 — 이 상태에만 그림이 있다. */
    READY,
    /** 장면 나누기 · 그리기 · 변환 중 하나가 실패했다. 그림 없이 편지만 보인다. */
    FAILED
}
