package com.momentory.retrospect.domain.strategy;

/**
 * 이번 턴의 질문을 누가 어떻게 붙이는가. 이름은 기록에 남는다.
 *
 * <p>규칙 v6 까지는 규칙이 질문의 초점(사건 · 자기 쪽 · 감정 · 이유 …)을 골랐다. v7 부터 규칙은 질문을 할지 말지만 정하고,
 * 무엇을 물을지는 대화 전체를 보는 문장 생성 모델이 정한다.
 */
public enum QuestionFocus {

    /** 질문 없음. */
    NONE,
    /** 문장 생성 모델이 고른 질문 하나. */
    OPEN,
    /** 다른 기억 — 이 이야기에서 더 물을 것이 없을 때. 문장은 서버의 고정 질문을 쓴다. */
    OTHER_TOPIC,
    /** 마무리 제안 — 여기까지 일기로 정리할지 묻는다. 문장과 선택지는 서버가 붙이고, 더 말하고 싶으면 이어간다. */
    WRAP_UP,

    // ── 아래는 규칙 v6 까지의 기록에만 있다 ──
    /** @deprecated v6 까지 */
    @Deprecated EVENT,
    /** @deprecated v6 까지 */
    @Deprecated SELF,
    /** @deprecated v6 까지 */
    @Deprecated FEELING,
    /** @deprecated v6 까지 */
    @Deprecated MEANING,
    /** @deprecated v6 까지 */
    @Deprecated FOLLOW_UP,
    /** @deprecated v6 까지 */
    @Deprecated EVIDENCE
}
