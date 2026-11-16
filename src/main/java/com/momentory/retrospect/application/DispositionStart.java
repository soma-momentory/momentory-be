package com.momentory.retrospect.application;

import java.util.List;
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

/**
 * 전략 대화를 시작할 때 지난 대화의 성향 관찰로 첫 신호를 정하고, 이 대화가 어느 집단인지 남긴다.
 *
 * <p>기본은 꺼져 있다({@code momentory.disposition.apply-mode=OFF}) — 그때는 모든 대화가 기본값에서 시작하고 집단은
 * {@code NONE} 으로 남는다. 도출된 성향은 집단과 무관하게 기록해, 반영한 대화와 「반영할 것이 있었지만 하지 않은」 대화를
 * 견줄 수 있게 한다. 기준값은 근거가 쌓이기 전의 임시값이다.
 */
@Component
public class DispositionStart {

    /** 대화 시작 계획 — 회고가 저장되기 전에 정하고, 저장된 뒤 {@link #open} 으로 남긴다. */
    public record Plan(DispositionArm arm, StartingDisposition derived, StrategySignals startingSignals) {
    }

    private final DispositionObservationRecordRepository observations;
    private final StrategyPolicy policy;
    private final DispositionAssignment assignment;
    private final int repeatThreshold;
    private final int window;
    private final double minConfidence;

    public DispositionStart(DispositionObservationRecordRepository observations, StrategyPolicy policy,
            @Value("${momentory.disposition.apply-mode:OFF}") DispositionAssignment.Mode mode,
            @Value("${momentory.disposition.apply-user-ids:}") Set<Long> userIds,
            @Value("${momentory.disposition.repeat-threshold:2}") int repeatThreshold,
            @Value("${momentory.disposition.window:5}") int window,
            @Value("${momentory.strategy.min-confidence:0.5}") double minConfidence) {
        this.observations = observations;
        this.policy = policy;
        this.assignment = new DispositionAssignment(mode, userIds);
        this.repeatThreshold = repeatThreshold;
        this.window = window;
        this.minConfidence = minConfidence;
    }

    public Plan plan(Long userId) {
        DispositionArm arm = assignment.armOf(userId);
        // 실험에 들지 않은 대화는 지난 관찰을 읽지 않는다 — 도출 기준이 정해지기 전의 값이 기록에 섞이지 않게 한다.
        if (arm == DispositionArm.NONE) {
            return new Plan(arm, StartingDisposition.NONE, StrategySignals.initial());
        }
        List<ConversationSignals> recent = observations
                .findByUserIdAndObservedTurnsGreaterThanOrderByCreatedAtDesc(userId, 0, PageRequest.of(0, window))
                .stream()
                .map(o -> new ConversationSignals(o.getResistanceCount(), o.getOtherViewRequestCount()))
                .toList();
        StartingDisposition derived = StartingDisposition.of(recent, repeatThreshold,
                policy.resistanceThreshold());
        StrategySignals signals = arm == DispositionArm.TREATMENT
                ? policy.startingSignals(derived.selfLed(), derived.wantsOtherView())
                : StrategySignals.initial();
        return new Plan(arm, derived, signals);
    }

    /** 방금 저장된 회고의 관찰 줄을 연다 — 답변이 오면 여기에 신호가 더해진다. */
    public void open(Long userId, Long retrospectId, Plan plan) {
        observations.save(DispositionObservationRecord.start(userId, retrospectId, minConfidence,
                StrategyPolicy.VERSION, plan.arm(), plan.derived()));
    }
}
