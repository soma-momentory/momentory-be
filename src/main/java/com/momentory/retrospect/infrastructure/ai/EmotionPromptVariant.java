package com.momentory.retrospect.infrastructure.ai;

/**
 * 감정 추출 프롬프트 변형 — 모델 비교의 실험 축 (계획 §3.5).
 *
 * <p>스키마는 고정하고 프롬프트만 갈아끼운다. 어떤 변형이 좋은지는 테스트 세트가 있어야 고를 수
 * 있으므로, 지금은 {@link #ZERO_SHOT} 하나만 구현하고 나머지는 자리만 잡아 둔다(계획 9장 6단계).
 *
 * <p><b>평가할 때는 temperature 를 0 으로 내린다</b> — 0.7 이면 같은 입력에 결과가 흔들려 버전 간
 * 차이인지 노이즈인지 구분할 수 없다.
 */
public enum EmotionPromptVariant {

    /** 예시 없이 규칙만 주고 바로 JSON 을 받는다. 현재 유일한 구현. */
    ZERO_SHOT,
    /** 감정별 예시를 넣은 few-shot — 강도·시점 앵커를 포함한다. */
    FEW_SHOT,
    /** 화남·답답·막막 경계 사례를 넣은 few-shot. */
    FEW_SHOT_BOUNDARY,
    /**
     * {@link #FEW_SHOT} 예시 + {@link #FEW_SHOT_BOUNDARY} 예시.
     *
     * <p><b>원본 문서에는 없는 축이다.</b> 경계 challenge 세트(20세션)에서 두 변형이 상호 보완적으로
     * 갈린 것을 보고 추가했다 — 경계 판정은 BOUNDARY 가(Macro F1 1.000), 강도·시점은 FEW_SHOT 이
     * (QWK 0.747 · 시점 F1 0.640) 나았고 어느 쪽도 지배하지 않았다.
     */
    FEW_SHOT_COMBINED,
    /** 근거 발화를 먼저 고르고 그다음 감정을 판단하는 2단계. 미구현. */
    TWO_STAGE
}
