package com.momentory.wish.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 소원 하나 — 쉼터 「소원 빌기」의 연못에 익명으로 7일 동안 떠 있는 글.
 *
 * <p>심사를 지나 고친 글만 저장한다. 고치기 전 원문과 거절된 글은 남기지 않는다. 한 사람은 하루
 * (04:00 경계)에 하나만 띄운다 — 지운 소원도 그날의 몫을 돌려주지 않으려고 행을 남기고 본문만 비운다.
 * 본문은 일기와 같은 등급의 사용자 글이라 로그에 남기지 않는다.
 */
@Entity
@Table(name = "wishes")
public class Wish extends BaseTimeEntity {

    public static final int CONTENT_MAX_LENGTH = 120;
    public static final Duration FLOATING_PERIOD = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wish_date", nullable = false)
    private LocalDate wishDate;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    /** 연못 쪽지에 돌려도 되는 가벼운 글인가 — 심사가 정한다. */
    @Column(nullable = false)
    private boolean light;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "taken_down_at")
    private Instant takenDownAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WishOutcome outcome;

    protected Wish() {
    }

    private Wish(Long userId, LocalDate wishDate, String content, boolean light, Instant expiresAt) {
        this.userId = userId;
        this.wishDate = wishDate;
        this.content = content;
        this.light = light;
        this.expiresAt = expiresAt;
    }

    public static Wish create(Long userId, LocalDate wishDate, String content, boolean light, Instant now) {
        return new Wish(userId, wishDate, content, light, now.plus(FLOATING_PERIOD));
    }

    public boolean isFloatingAt(Instant now) {
        return deletedAt == null && takenDownAt == null && expiresAt.isAfter(now);
    }

    public void takeDown(Instant now) {
        if (takenDownAt == null) {
            takenDownAt = now;
        }
    }

    public void delete(Instant now) {
        deletedAt = now;
        content = "";
    }

    public void answer(WishOutcome outcome) {
        this.outcome = outcome;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getTakenDownAt() {
        return takenDownAt;
    }

    public WishOutcome getOutcome() {
        return outcome;
    }
}
