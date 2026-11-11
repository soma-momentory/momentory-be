package com.momentory.retrospect.domain.strategy;

/** 이번 턴 질문이 겨냥하는 것. {@link #NONE} 이면 질문을 붙이지 않는다. */
public enum QuestionFocus {

    /** 질문 없음. */
    NONE,
    /** 무슨 일이 있었는지 — 사건 설명이 아직 없거나 일부뿐일 때. */
    EVENT,
    /** 그 일이 나에게 어땠는지 — 사건·타인 얘기(체험 1~2단계)에서 자기 쪽으로. */
    SELF,
    /** 그 감정이 어떤 느낌이었는지 — 사건에 대한 반응(3단계)에서 감정 자체로. */
    FEELING,
    /** 그 감정이 어디서 왔는지 — 자기 감정이 주제(4단계)일 때 그 의미로. */
    MEANING,
    /** 그렇게 생각한 근거 — 재해석. */
    EVIDENCE,
    /** 다른 기억 — 이 소재에서 더 깊어지지 않을 때. 문장은 서버의 고정 질문을 쓴다. */
    OTHER_TOPIC
}
