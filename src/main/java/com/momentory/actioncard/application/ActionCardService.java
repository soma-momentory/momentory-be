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

    /**
     * 행동 카드 한 장을 지운다 — 소유권을 함께 검증한다(남의 카드면 없는 것처럼 404).
     *
     * <p>일기 삭제({@code DiaryService#delete})와 같은 결이다. 예전엔 서버에 삭제 계약이 없어
     * 클라이언트가 지운 id 를 기기에 남겨 조회 때 걸러냈는데, 그 우회로를 이 엔드포인트가 대신한다.
     * 회고를 막 끝낸 카드도 완료 응답이 준 서버 id 로 그 자리에서 지울 수 있다.
     */
    @Transactional
    public void delete(Long userId, Long id) {
        ActionCard card = actionCardRepository.findByIdAndUserId(id, userId)
                .orElseThrow(ActionCardNotFoundException::new);
        actionCardRepository.delete(card);
    }
}
