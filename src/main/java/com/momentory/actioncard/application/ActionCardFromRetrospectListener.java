package com.momentory.actioncard.application;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.application.RetrospectCompleted;

/**
 * 회고 완료 이벤트를 받아 행동 카드를 남기는 actioncard 컨텍스트의 진입점 — 쓰기 방향에서 retrospect
 * 와의 결합을 이벤트로 끊는다(retrospect 는 카드를 어떻게 저장하는지 모른다). 카드가 없는 방향의
 * 완료면(이벤트의 actionCard 가 null) 아무것도 안 한다.
 *
 * <p><b>{@code @EventListener} 는 같은 트랜잭션에서 동기로 실행된다</b> — 카드 저장 실패는 회고 턴째
 * 롤백된다. 다만 상황 <b>임베딩</b>은 있으면 좋은 부가물이라, 카드가 커밋된 뒤 별도 트랜잭션으로
 * 채운다({@link #scheduleEmbedding} → {@code afterCommit}). 커밋 전에 채우면 카드 행이 아직 안 보여
 * 헛돌고, 실패해도 카드는 남아야 하기 때문이다.
 *
 * <p>회고 한 벌에 카드 한 장이라, 이미 있으면 만들지 않는다.
 */
@Component
public class ActionCardFromRetrospectListener {

    private static final Logger log = LoggerFactory.getLogger(ActionCardFromRetrospectListener.class);

    private final ActionCardRepository actionCardRepository;
    private final ActionCardEmbedder actionCardEmbedder;

    public ActionCardFromRetrospectListener(ActionCardRepository actionCardRepository,
            ActionCardEmbedder actionCardEmbedder) {
        this.actionCardRepository = actionCardRepository;
        this.actionCardEmbedder = actionCardEmbedder;
    }

    @EventListener
    public void on(RetrospectCompleted event) {
        RetrospectCompleted.ActionCardData card = event.actionCard();
        if (card == null || actionCardRepository.existsByRetrospectId(event.retrospectId())) {
            return;
        }
        ActionCard saved = actionCardRepository.save(ActionCard.create(event.userId(),
                event.retrospectId(), card.situation(), card.action(), LocalDate.now(),
                card.fromRestPreference()));
        scheduleEmbedding(saved.getId(), card.situation());
    }

    /**
     * 카드가 커밋된 뒤 상황 임베딩을 채운다 — 다음 회고에서 "비슷한 상황"으로 되살릴 열쇠다.
     * 실패해도 삼킨다({@link ActionCardEmbedder} 가 별도 트랜잭션이라 카드 트랜잭션엔 영향이 없다).
     */
    private void scheduleEmbedding(Long cardId, String situation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            embedQuietly(cardId, situation); // 트랜잭션 밖(방어) — 바로 시도한다.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                embedQuietly(cardId, situation);
            }
        });
    }

    private void embedQuietly(Long cardId, String situation) {
        try {
            actionCardEmbedder.embedAndStore(cardId, situation);
        } catch (Exception e) {
            log.warn("행동 카드 임베딩 저장 실패(카드는 유지됨) cardId={}: {}", cardId, e.toString());
        }
    }
}
