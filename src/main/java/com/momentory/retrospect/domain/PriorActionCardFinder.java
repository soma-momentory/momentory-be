package com.momentory.retrospect.domain;

import java.util.Optional;

/**
 * 지금 상황과 비슷한, 사용자가 전에 만든 행동 카드를 찾는 포트.
 *
 * <p>엔진은 무상태·userId 무지라 직접 조회할 수 없다 — 서비스가 userId·임베딩·저장소를 묶어
 * 구현을 만들어 매 턴 {@link RetrospectState} 에 실어준다. 엔진은 행동 추천을 낼 때만 부른다
 * (지연 조회 — 필요 없는 턴엔 임베딩 호출도 없다).
 *
 * <p>매칭이 없으면 {@link Optional#empty()}. 기본값 {@link #NONE} 은 아무것도 찾지 않는다
 * (서비스가 설정하지 않은 경우·테스트).
 */
@FunctionalInterface
public interface PriorActionCardFinder {

    PriorActionCardFinder NONE = situation -> Optional.empty();

    Optional<PriorActionCard> findSimilar(String situation);
}
