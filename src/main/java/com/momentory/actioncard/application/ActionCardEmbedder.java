package com.momentory.actioncard.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.actioncard.application.SituationEmbedder;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.actioncard.infrastructure.persistence.VectorLiteral;

/**
 * 행동 카드의 '상황' 임베딩을 채우는 부가 작업 — 다음 회고에서 "비슷한 상황"으로 되살릴 열쇠다.
 *
 * <p><b>카드 저장과 분리된 별도 트랜잭션</b>({@link Propagation#REQUIRES_NEW})으로 돈다. 임베딩은
 * 있으면 좋은 부가물일 뿐이라, 여기서 실패하더라도 이미 커밋된 카드까지 함께 롤백되면 안 되기
 * 때문이다. 실패는 호출자({@link ActionCardFromRetrospectListener})가 삼킨다 — 카드는 그대로 남는다.
 */
@Component
public class ActionCardEmbedder {

    private final SituationEmbedder situationEmbedder;
    private final ActionCardRepository actionCardRepository;

    public ActionCardEmbedder(SituationEmbedder situationEmbedder,
            ActionCardRepository actionCardRepository) {
        this.situationEmbedder = situationEmbedder;
        this.actionCardRepository = actionCardRepository;
    }

    /**
     * 이미 저장된 카드({@code cardId})의 상황을 임베딩해 채운다. 임베딩이 비면(모델 실패 등) 조용히
     * 넘어가고, 그 외 오류는 예외로 던져 이 트랜잭션만 되돌린다(카드 트랜잭션과 무관하다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embedAndStore(Long cardId, String situation) {
        situationEmbedder.embed(situation)
                .ifPresent(vec -> actionCardRepository.updateEmbedding(cardId, VectorLiteral.of(vec)));
    }
}
