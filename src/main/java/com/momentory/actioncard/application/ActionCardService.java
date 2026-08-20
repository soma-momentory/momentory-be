package com.momentory.actioncard.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;

/**
 * 행동 카드 쓰기 유스케이스 — "해봤어요"/되돌리기와 느낀 점 반영. 조회는
 * {@link ActionCardQueryService}, 생성은 회고 완료 흐름({@link ActionCardFromRetrospectListener})
 * 이 맡는다.
 */
@Service
public class ActionCardService {

    private final ActionCardRepository actionCardRepository;

    public ActionCardService(ActionCardRepository actionCardRepository) {
        this.actionCardRepository = actionCardRepository;
    }

    /**
     * "해봤어요"/되돌리기 + 느낀 점을 반영한다 — 소유권을 함께 검증한다.
     * 상태 규칙(해본 시각 유지·되돌릴 때 함께 비움)은 도메인이 지킨다.
     */
    @Transactional
    public ActionCardView changeCompletion(Long userId, Long id, boolean done, String reflection) {
        ActionCard card = actionCardRepository.findByIdAndUserId(id, userId)
                .orElseThrow(ActionCardNotFoundException::new);
        card.changeCompletion(done, reflection, Instant.now());
        return ActionCardView.from(card);
    }
}
