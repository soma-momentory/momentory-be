package com.momentory.letter.domain;

/** 작은 일 한 칸이 어떻게 채워졌는지. */
public enum LetterActionSource {
    /** AI 가 연결표 후보 중에서 고른 행동. */
    CATALOG,
    /** AI 없이 연결표 순서대로 채운 행동(대체 규칙). */
    FALLBACK
}
