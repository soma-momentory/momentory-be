package com.momentory.letter.domain;

/** 작은 일 추천에 쓴 욕구가 어디서 왔는지. */
public enum NeedsSource {
    /** 그 일기의 바람카드에서 사용자가 확인한 욕구. */
    WISH_CARD,
    /** 바람카드가 없어 일기를 보고 AI 가 고른 욕구. */
    INFERRED,
    /** 욕구를 모를 때 쓰는 「휴식」. */
    DEFAULT
}
