package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

/**
 * 행동 카드 '상황' 문장을 의미 벡터로 바꾸는 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>"비슷한 상황에서 만든 이전 카드"를 찾기 위한 임베딩이다. 저장 시엔 그 카드의 상황을,
 * 추천 시엔 지금 회고의 상황을 벡터로 만들어 코사인 최근접으로 잇는다.
 *
 * <p>실패하면 {@link Optional#empty()} 를 돌려준다 — 임베딩 장애로 회고가 끊기면 안 되므로,
 * 매칭이 없는 것으로 조용히 접는다(추천 첫 줄이 빠질 뿐 대화는 계속된다).
 */
public interface SituationEmbedder {

    Optional<float[]> embed(String text);
}
