package com.momentory.actioncard.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.actioncard.infrastructure.persistence.VectorLiteral;
import com.momentory.retrospect.application.PriorActionCardRecommender;
import com.momentory.retrospect.domain.PriorActionCard;

/**
 * {@link PriorActionCardRecommender} 의 actioncard 쪽 구현 — 지금 회고의 상황을 임베딩해, 이 사용자의
 * 행동 카드 중 코사인으로 가장 가까운 한 장을 찾는다. 유사도·임베딩은 actioncard 컨텍스트의 몫이라
 * 여기 둔다(retrospect 는 포트만 안다).
 *
 * <p>임베딩 실패·매칭 없음이면 {@link Optional#empty()} — 추천 첫 줄이 빠질 뿐 대화는 계속된다.
 */
@Component
public class SituationBasedRecommender implements PriorActionCardRecommender {

    /**
     * 코사인 거리 임계값 — 이 미만이어야 "비슷한 상황"으로 본다(0=동일, 2=정반대).
     * 너무 크면 관계없는 카드가 추천되고, 너무 작으면 거의 안 걸린다. 튜닝 지점.
     */
    private static final double SIMILAR_MAX_DISTANCE = 0.35;

    private final SituationEmbedder situationEmbedder;
    private final ActionCardRepository actionCardRepository;

    public SituationBasedRecommender(SituationEmbedder situationEmbedder,
            ActionCardRepository actionCardRepository) {
        this.situationEmbedder = situationEmbedder;
        this.actionCardRepository = actionCardRepository;
    }

    @Override
    public Optional<PriorActionCard> findSimilar(Long userId, String situation) {
        return situationEmbedder.embed(situation)
                .flatMap(vec -> actionCardRepository.findMostSimilar(userId, VectorLiteral.of(vec),
                        SIMILAR_MAX_DISTANCE))
                .map(c -> new PriorActionCard(c.getTargetAction(), c.getSituation(),
                        c.getCreatedDate()));
    }
}
