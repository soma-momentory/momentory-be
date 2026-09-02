package com.momentory.retrospect.domain.assistant;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * 감정 추출 포트 — 일기 작성 대화 전체에서 사건(≤2)과 감정을 뽑아 {@link EmotionExtraction} 으로 준다.
 *
 * <p><b>대화 엔진이 감정을 판단하는 유일한 경계다.</b> 일기 작성이 끝나는 시점에 대화 전체를 근거로
 * 한 번 호출한다(매 턴 호출하지 않는다 — 비용·지연 최소). 뽑은 감정은 일기의 감정 태그와 감정 탐색
 * 1턴의 후보로 함께 쓰인다.
 *
 * <p>지금은 LLM 어댑터가 구현하지만, 나중에 전용 감정 분류 모델로 <b>공급자만 교체</b>할 수 있도록
 * 엔진에서 감정 판단을 이 인터페이스 뒤로 분리한다(채팅흐름_v2 결정 ②).
 *
 * <p>실패하면 {@link EmotionExtraction#empty()} 를 준다 — 감정 슬롯을 못 채운 것으로 두고 흐름은
 * 계속된다(던지지 않는다).
 */
@FunctionalInterface
public interface EmotionExtractor {

    EmotionExtraction extract(RetrospectState state);
}
