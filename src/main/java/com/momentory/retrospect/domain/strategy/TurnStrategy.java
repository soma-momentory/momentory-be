package com.momentory.retrospect.domain.strategy;

/**
 * 이번 턴의 전략 — 유형과 질문 조절값.
 *
 * @param type      전략 유형
 * @param focus     질문이 겨냥하는 것. NONE 이면 질문 없음
 * @param asChoices 질문을 고를 수 있는 선택지 형태로 가볍게 묻는가(저항이 반복된 사용자)
 * @param brief     방금 답변이 「맞아」「응」 같은 짧은 동의뿐인가 — 앞의 공감을 되풀이하지 않고 바로 다음 질문으로 간다
 * @param struggled 방금 답변이 답하기 어려워한 것인가 — 그 답을 되풀이하지 않고 어려울 수 있다는 것만 받아준다
 */
public record TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, boolean brief,
        boolean struggled) {

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices) {
        this(type, focus, asChoices, false, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, boolean brief) {
        this(type, focus, asChoices, brief, false);
    }

    public boolean asks() {
        return focus != QuestionFocus.NONE;
    }
}
