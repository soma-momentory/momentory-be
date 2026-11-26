package com.momentory.retrospect.application;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.disposition.ConversationSignals;
import com.momentory.retrospect.domain.disposition.DispositionArm;
import com.momentory.retrospect.domain.disposition.DispositionAssignment;
import com.momentory.retrospect.domain.disposition.StartingDisposition;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategySignals;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecord;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecordRepository;

/** 실험군은 매 회고의 시작에 세 성향을 적용하고, 대조군은 매번 초기 상태에서 시작한다. */
@Component
public class DispositionStart {

    public record Plan(DispositionArm arm, StartingDisposition derived, StrategySignals startingSignals) {
    }

    private final DispositionObservationRecordRepository observations;
    private final StrategyPolicy policy;
    private final DispositionAssignment assignment;
    private final int window;
    private final double minConfidence;

    public DispositionStart(DispositionObservationRecordRepository observations, StrategyPolicy policy,
            @Value("${momentory.disposition.apply-mode:OFF}") DispositionAssignment.Mode mode,
            @Value("${momentory.disposition.apply-user-ids:}") Set<Long> userIds,
            @Value("${momentory.disposition.excluded-user-ids:}") Set<Long> excludedUserIds,
            @Value("${momentory.disposition.window:5}") int window,
            @Value("${momentory.strategy.min-confidence:0.5}") double minConfidence) {
        if (window < 1) {
            throw new IllegalArgumentException("성향을 읽을 회고 수는 1 이상이어야 합니다");
        }
        this.observations = observations;
        this.policy = policy;
        this.assignment = new DispositionAssignment(mode, userIds, excludedUserIds);
        this.window = window;
        this.minConfidence = minConfidence;
    }

    public Plan plan(Long userId) {
        DispositionArm arm = assignment.armOf(userId);
        if (arm == DispositionArm.NONE) {
            return new Plan(arm, StartingDisposition.NONE, StrategySignals.initial());
        }
        StartingDisposition derived = StartingDisposition.of(observations
                .findCompletedByUserId(userId, PageRequest.of(0, window))
                .stream()
                .map(o -> new ConversationSignals(o.getResistanceCount(), o.getListenRequestCount(),
                        o.getOtherViewRequestCount(), o.getFeelingExpressedCount(), o.getFeelingAvoidedCount()))
                .toList());
        return new Plan(arm, derived, arm == DispositionArm.TREATMENT
                ? policy.startingSignals(derived) : StrategySignals.initial());
    }

    public void open(Long userId, Long retrospectId, Plan plan) {
        observations.save(DispositionObservationRecord.start(userId, retrospectId, minConfidence,
                StrategyPolicy.VERSION, plan.arm(), plan.derived()));
    }
}
