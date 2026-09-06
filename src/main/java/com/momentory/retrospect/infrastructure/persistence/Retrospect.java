package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

import com.momentory.common.persistence.BaseTimeEntity;
import com.momentory.retrospect.domain.RetrospectStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회고 세션의 영속 스냅샷.
 *
 * <p>도메인 애그리거트는 순수 {@code RetrospectState} 이고, 이 엔티티는 그 진행 상태를 통째로
 * {@code state_json} 에 담아 보관하는 얇은 래퍼다(스냅샷 방식). 목록·조회·수명주기에 필요한
 * 값만 별도 컬럼으로 뽑아 둔다. 사용자는 스칼라 {@code userId} 로 참조한다(be 관례).
 *
 * <p>v2 에서 {@code mode}(회고 모드)·{@code current_emotion}(시작 감정) 컬럼을 제거했다 — 대화가
 * 모드 분기 없이 진행되고 감정은 시작 시 고르지 않는다. 감정은 완료 시 일기 쪽에 저장된다.
 */
@Entity
@Table(name = "retrospects")
public class Retrospect extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RetrospectStatus status;

    /**
     * 개인화 소재로 고른 일정 — schedules 테이블 id 참조(스칼라, be 관례). 특정 일정 없는 '오늘 하루'
     * 회고나 목록 밖 자유 입력 일정이면 null. 일정 이름·감정은 {@code state_json} 안에 있다.
     */
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Retrospect() {
    }

    private Retrospect(Long userId, RetrospectStatus status, Long scheduleId, String stateJson) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.stateJson = Objects.requireNonNull(stateJson, "stateJson must not be null");
        this.scheduleId = scheduleId;
    }

    public static Retrospect start(Long userId, RetrospectStatus status, Long scheduleId,
            String stateJson) {
        return new Retrospect(userId, status, scheduleId, stateJson);
    }

    /** 한 턴 처리 후 진행 상태를 반영한다. 완료 시에만 완료시각이 채워진다(일기는 별도 테이블). */
    public void sync(RetrospectStatus status, String stateJson, Instant completedAt) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.stateJson = Objects.requireNonNull(stateJson, "stateJson must not be null");
        if (completedAt != null) {
            this.completedAt = completedAt;
        }
    }

    public boolean belongsTo(Long userId) {
        return this.userId.equals(userId);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public RetrospectStatus getStatus() {
        return status;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public String getStateJson() {
        return stateJson;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
