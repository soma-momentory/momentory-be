package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

import com.momentory.retrospect.domain.disposition.DispositionArm;
import com.momentory.retrospect.domain.disposition.StartingDisposition;
import com.momentory.retrospect.domain.disposition.TurnObservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 대화 한 건에서 관찰한 성향 신호의 횟수 — 회고마다 한 줄이고, 답변이 올 때마다 더해진다(대화를 끝내지 않고 떠나도 남는다).
 *
 * <p>성향 값(어느 쪽인지)은 담지 않는다. 몇 번부터 어느 쪽으로 볼지, 최근 대화에 얼마나 무게를 둘지는 아직 정해지지 않아,
 * 나중에 이 줄들을 읽어 계산한다. 발화 원문은 담지 않는다.
 */
@Entity
@Table(name = "retrospect_disposition_observations")
public class DispositionObservationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Column(name = "observed_turns", nullable = false)
    private int observedTurns;

    @Column(name = "resistance_count", nullable = false)
    private int resistanceCount;

    @Column(name = "listen_request_count", nullable = false)
    private int listenRequestCount;

    @Column(name = "other_view_request_count", nullable = false)
    private int otherViewRequestCount;

    @Column(name = "feeling_question_count", nullable = false)
    private int feelingQuestionCount;

    @Column(name = "feeling_expressed_count", nullable = false)
    private int feelingExpressedCount;

    @Column(name = "feeling_avoided_count", nullable = false)
    private int feelingAvoidedCount;

    /** 바바의 마지막 말이 기분 질문이었는가 — 다음 답변을 그 물음에 대한 반응으로 센다. */
    @Column(name = "awaiting_feeling_answer", nullable = false)
    private boolean awaitingFeelingAnswer;

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    /** 이 대화가 든 실험 집단 — NONE · TREATMENT · CONTROL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "apply_arm", nullable = false, length = 20)
    private DispositionArm applyArm;

    /** 지난 대화들에서 도출된 시작 성향 — 집단과 무관하게 남긴다. 실제 반영은 TREATMENT 일 때만이다. */
    @Column(name = "derived_self_led", nullable = false)
    private boolean derivedSelfLed;

    @Column(name = "derived_other_view", nullable = false)
    private boolean derivedOtherView;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DispositionObservationRecord() {
    }

    /** 실험에 들지 않은 대화의 관찰 — 반영 기능이 생기기 전에 시작한 회고가 여기로 온다. */
    public static DispositionObservationRecord start(Long userId, Long retrospectId, double minConfidence,
            String policyVersion) {
        return start(userId, retrospectId, minConfidence, policyVersion, DispositionArm.NONE,
                StartingDisposition.NONE);
    }

    /**
     * @param arm     이 대화가 든 집단
     * @param derived 지난 대화들에서 도출된 시작 성향
     */
    public static DispositionObservationRecord start(Long userId, Long retrospectId, double minConfidence,
            String policyVersion, DispositionArm arm, StartingDisposition derived) {
        DispositionObservationRecord record = new DispositionObservationRecord();
        record.applyArm = Objects.requireNonNull(arm, "arm must not be null");
        record.derivedSelfLed = derived.selfLed();
        record.derivedOtherView = derived.wantsOtherView();
        record.userId = Objects.requireNonNull(userId, "userId must not be null");
        record.retrospectId = Objects.requireNonNull(retrospectId, "retrospectId must not be null");
        record.minConfidence = minConfidence;
        record.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        record.createdAt = Instant.now();
        record.updatedAt = record.createdAt;
        return record;
    }

    /** 답변 하나에서 읽은 신호를 더한다. */
    public void add(TurnObservation turn, String policyVersion) {
        observedTurns++;
        resistanceCount += turn.resisted() ? 1 : 0;
        listenRequestCount += turn.askedToListen() ? 1 : 0;
        otherViewRequestCount += turn.askedOtherView() ? 1 : 0;
        feelingExpressedCount += turn.feelingExpressed() ? 1 : 0;
        feelingAvoidedCount += turn.feelingAvoided() ? 1 : 0;
        feelingQuestionCount += turn.babaAskedFeeling() ? 1 : 0;
        awaitingFeelingAnswer = turn.babaAskedFeeling();
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public Long getRetrospectId() { return retrospectId; }
    public int getObservedTurns() { return observedTurns; }
    public int getResistanceCount() { return resistanceCount; }
    public int getListenRequestCount() { return listenRequestCount; }
    public int getOtherViewRequestCount() { return otherViewRequestCount; }
    public int getFeelingQuestionCount() { return feelingQuestionCount; }
    public int getFeelingExpressedCount() { return feelingExpressedCount; }
    public int getFeelingAvoidedCount() { return feelingAvoidedCount; }
    public boolean isAwaitingFeelingAnswer() { return awaitingFeelingAnswer; }
    public double getMinConfidence() { return minConfidence; }
    public String getPolicyVersion() { return policyVersion; }
    public DispositionArm getApplyArm() { return applyArm; }
    public boolean isDerivedSelfLed() { return derivedSelfLed; }
    public boolean isDerivedOtherView() { return derivedOtherView; }
}
