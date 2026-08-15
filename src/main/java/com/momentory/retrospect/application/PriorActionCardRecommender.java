package com.momentory.retrospect.application;

import java.util.Optional;

import com.momentory.retrospect.domain.PriorActionCard;

/**
 * "지금 상황과 비슷한, 사용자가 전에 만든 행동 카드"를 찾는 포트 — retrospect 가 정의하고 actioncard
 * 컨텍스트가 구현한다(회고는 추천을 어떻게 만드는지 모르고, actioncard 가 임베딩·유사도 검색을
 * 소유한다). 회고 진행 중 행동 추천 스텝에서만(지연) 부른다.
 *
 * <p>엔진이 쓰는 {@link com.momentory.retrospect.domain.PriorActionCardFinder} 는 userId 가 이미
 * 묶인 턴 스코프 포트이고, 이쪽은 서비스가 userId 를 넘겨 부르는 상위 포트다. 매칭·임베딩이 없으면
 * {@link Optional#empty()}.
 */
public interface PriorActionCardRecommender {

    Optional<PriorActionCard> findSimilar(Long userId, String situation);
}
