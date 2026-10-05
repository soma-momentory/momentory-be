package com.momentory.treasure.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 오늘의 보물 — 쉼터 「오늘의 보물함」에 담은 소중한 순간 한 줄.
 *
 * <p>하루에 여러 개를 담을 수 있다. 속한 하루({@code treasureDate})는 담은 순간의
 * {@link com.momentory.common.time.DayBoundary 04:00 경계} 날짜이고, 담은 시각은
 * {@code createdAt} 이다. 본문은 일기와 같은 등급의 사용자 글이라 로그에 남기지 않는다.
 */
@Entity
@Table(name = "treasures")
public class Treasure extends BaseTimeEntity {

    public static final int CONTENT_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "treasure_date", nullable = false)
    private LocalDate treasureDate;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    protected Treasure() {
    }

    private Treasure(Long userId, LocalDate treasureDate, String content) {
        this.userId = userId;
        this.treasureDate = treasureDate;
        this.content = content;
    }

    public static Treasure create(Long userId, LocalDate treasureDate, String content) {
        return new Treasure(userId, treasureDate, content);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTreasureDate() {
        return treasureDate;
    }

    public String getContent() {
        return content;
    }
}
