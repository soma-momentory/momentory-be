package com.momentory.wish.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 같이 빌기 한 번 — 한 사람은 한 소원에 한 번만 남는다. */
@Entity
@Table(name = "wish_joins")
public class WishJoin extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wish_id", nullable = false)
    private Long wishId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    protected WishJoin() {
    }

    public Long getWishId() {
        return wishId;
    }
}
