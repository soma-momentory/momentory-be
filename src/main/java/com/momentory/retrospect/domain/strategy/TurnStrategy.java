package com.momentory.retrospect.domain.strategy;

/**
 * 이번 턴의 전략 — 유형과 질문 조절값.
 *
 * @param type      전략 유형
 * @param focus     질문이 겨냥하는 것. NONE 이면 질문 없음
 * @param asChoices 질문을 고를 수 있는 선택지 형태로 가볍게 묻는가(저항이 반복된 사용자)
 */
public record TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices) {

    public boolean asks() {
        return focus != QuestionFocus.NONE;
    }
}
