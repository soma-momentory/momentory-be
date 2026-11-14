package com.momentory.retrospect.infrastructure.persistence;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.disposition.TurnObservation;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;

/**
 * 전략 대화의 턴 기록에서 성향 신호를 세어 대화별 관찰에 더한다 — 회고 턴 트랜잭션 안에서 부르므로 턴이 롤백되면 함께 없다.
 * 모델을 부르지 않는다. 전략이 고른 턴만 센다(판단만 기록하는 계정의 옛 흐름은 바바의 질문이 달라 같은 신호로 볼 수 없다).
 */
@Component
public class DispositionObservationRecorder {

    private final DispositionObservationRecordRepository repository;
    private final double minConfidence;

    public DispositionObservationRecorder(DispositionObservationRecordRepository repository,
            @Value("${momentory.strategy.min-confidence:0.5}") double minConfidence) {
        this.repository = repository;
        this.minConfidence = minConfidence;
    }

    public void record(Long userId, Long retrospectId, List<TurnJudgmentLog> logs) {
        for (TurnJudgmentLog log : logs) {
            if (log.strategy() == null) {
                continue;
            }
            DispositionObservationRecord observation = repository.findByRetrospectId(retrospectId)
                    .orElseGet(() -> DispositionObservationRecord.start(userId, retrospectId, minConfidence,
                            log.policyVersion()));
            observation.add(TurnObservation.of(log, observation.isAwaitingFeelingAnswer(), minConfidence),
                    log.policyVersion());
            repository.save(observation);
        }
    }
}
