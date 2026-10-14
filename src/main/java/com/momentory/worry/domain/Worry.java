package com.momentory.worry.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 맡겨둔 걱정 — 쉼터 「걱정상자」에서 「잠시 맡겨둘게요」를 고른 걱정 한 줄.
 *
 * <p>「흘려보낼게요」를 고른 걱정은 여기 오지 않는다 — 어디에도 남기지 않는다. 속한 하루
 * ({@code worryDate})는 맡긴 순간의 {@link com.momentory.common.time.DayBoundary 04:00 경계}
 * 날짜이고, 맡긴 시각은 {@code createdAt} 이다. 본문은 일기와 같은 등급의 사용자 글이라
 * 로그에 남기지 않는다.
 */
@Entity
@Table(name = "worries")
public class Worry extends BaseTimeEntity {

    public static final int CONTENT_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "worry_date", nullable = false)
    private LocalDate worryDate;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    protected Worry() {
    }

    private Worry(Long userId, LocalDate worryDate, String content) {
        this.userId = userId;
        this.worryDate = worryDate;
        this.content = content;
    }

    public static Worry create(Long userId, LocalDate worryDate, String content) {
        return new Worry(userId, worryDate, content);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getWorryDate() {
        return worryDate;
    }

    public String getContent() {
        return content;
    }
}
