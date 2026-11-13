package com.momentory.retrospect.domain.strategy;

/**
 * 이번 턴의 방침 — 유형과 질문 조절값.
 *
 * @param type      방침의 유형
 * @param focus     질문을 누가 붙이는가. NONE 이면 질문 없음
 * @param asChoices 질문을 고를 수 있는 선택지 형태로 가볍게 묻는가(저항이 반복된 사용자)
 * @param reaction  판단 모델이 본 「직전 응답에 대한 반응」 — 문장 생성 모델에 참고로 건넨다. 볼 것이 없으면 null
 */
public record TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction) {

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices) {
        this(type, focus, asChoices, null);
    }

    public boolean asks() {
        return focus != QuestionFocus.NONE;
    }

    public TurnStrategy withFocus(QuestionFocus newFocus) {
        return new TurnStrategy(type, newFocus, asChoices, reaction);
    }
}
