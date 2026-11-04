package com.momentory.wish.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 소원 연못 이용 규칙에 동의한 기록 — 첫 소원 전에 한 번. 규칙이 바뀌면 판을 올려 다시 받는다. */
@Entity
@Table(name = "wish_rule_agreements")
public class WishRuleAgreement {

    public static final int CURRENT_VERSION = 1;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private int version;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    protected WishRuleAgreement() {
    }

    public WishRuleAgreement(Long userId, int version, Instant agreedAt) {
        this.userId = userId;
        this.version = version;
        this.agreedAt = agreedAt;
    }

    public int getVersion() {
        return version;
    }
}
