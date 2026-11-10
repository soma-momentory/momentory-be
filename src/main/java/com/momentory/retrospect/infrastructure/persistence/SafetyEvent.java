package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회고 중 위기 안내(RISK 이상)가 나간 기록. 행은 {@link SafetyEventRepository#record} 의 네이티브 insert 로만
 * 생긴다 — 이 엔티티는 조회용이다. 편지는 「일기 날 이후 이 기록이 있는가」로 조용한 편지를 정한다.
 */
@Entity
@Table(name = "safety_events")
public class SafetyEvent {

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Column(nullable = false, length = 10)
    private String level;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SafetyEvent() {
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRetrospectId() {
        return retrospectId;
    }

    public String getLevel() {
        return level;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
