package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.momentory.common.persistence.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 행동 카드 — 회고 한 벌이 남기는 "🪪 행동 카드".
 *
 * <p>일기(회고의 {@code diary} 컬럼)와 달리 조회·목록·연결을 독립적으로 하기 위해 별도 테이블로
 * 뽑는다: 보관함에서 카드만 따로 훑고, {@code retrospectId} 로 그날의 일기(회고)로 되돌아가며,
 * {@code situation} 으로 "같은 상황에서 만든 이전 카드"를 찾는다. <b>회고 한 벌에 카드 한 장</b>
 * (테이블의 {@code uk_action_cards_retrospect}).
 *
 * <p>{@code done}·{@code doneAt}·{@code reflection} 은 사용자가 "해봤어요"를 누른 뒤에 채워지는
 * 상태다. 생성 시점엔 아직 비어 있다.
 */
@Entity
@Table(name = "action_cards")
public class ActionCard extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Column(nullable = false, length = 500)
    private String situation;

    @Column(name = "target_action", nullable = false, length = 255)
    private String targetAction;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_date", nullable = false)
    private LocalDate createdDate;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(columnDefinition = "TEXT")
    private String reflection;

    protected ActionCard() {
    }

    private ActionCard(Long userId, Long retrospectId, String situation, String targetAction,
            String detail, LocalDate createdDate) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.retrospectId = Objects.requireNonNull(retrospectId, "retrospectId must not be null");
        this.situation = Objects.requireNonNull(situation, "situation must not be null");
        this.targetAction = Objects.requireNonNull(targetAction, "targetAction must not be null");
        this.createdDate = Objects.requireNonNull(createdDate, "createdDate must not be null");
        this.detail = detail;
        this.done = false;
    }

    /** 회고 완료 시 생성 — 아직 해보지 않은(미완료) 상태로 남는다. */
    public static ActionCard create(Long userId, Long retrospectId, String situation,
            String targetAction, String detail, LocalDate createdDate) {
        return new ActionCard(userId, retrospectId, situation, targetAction, detail, createdDate);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRetrospectId() {
        return retrospectId;
    }

    public String getSituation() {
        return situation;
    }

    public String getTargetAction() {
        return targetAction;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public boolean isDone() {
        return done;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public String getReflection() {
        return reflection;
    }
}
