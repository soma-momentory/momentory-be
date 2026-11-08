package com.momentory.wish.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 신고 한 건 — 신고한 사람에게는 그 작성자의 소원이 더 보이지 않는다. */
@Entity
@Table(name = "wish_reports")
public class WishReport extends BaseTimeEntity {

    /** 서로 다른 몇 명이 신고하면 연못에서 내리는가. */
    public static final int TAKE_DOWN_REPORTERS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wish_id", nullable = false)
    private Long wishId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    protected WishReport() {
    }

    public WishReport(Long wishId, Long reporterId, Long authorId) {
        this.wishId = wishId;
        this.reporterId = reporterId;
        this.authorId = authorId;
    }
}
