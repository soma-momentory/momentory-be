package com.momentory.retrospect.domain.strategy;

/** 이번 턴에 바바가 할 일의 유형 다섯 가지. 이름은 기록에 남으므로 바꾸면 이전 기록과 비교할 수 없다. */
public enum StrategyType {

    /** 마무리 — 추가 질문 없이 짧게 끝맺는다. */
    CLOSE,
    /** 수정 반영 — 사용자가 고친 표현으로 되돌려준다. */
    CORRECT,
    /** 공감·반영 — 말한 감정과 맥락을 받아준다. */
    REFLECT,
    /** 탐색 — 인정 한 문장 뒤에 아직 안 나온 것 하나를 묻는다. */
    EXPLORE,
    /** 재해석 — 인정 한 문장 뒤에 그 생각의 근거를 함께 보는 질문을 한다. */
    REFRAME
}
