package com.momentory.weeklyhappiness.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import com.momentory.common.time.DayBoundary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 주간 행복 한 칸 — 쉼터 「주간 행복」 기록장에서 한 주의 한 칸에 적은 글.
 *
 * <p>사용자 · 주 · 칸마다 글 하나다. 주는 일~토이고 일요일 04:00(KST · {@link DayBoundary})에 바뀐다 —
 * {@code weekStart} 는 그 주의 일요일이다. <b>쓰는 것은 이번 주뿐이고 지난 주는 읽기만 한다.</b> 비운 칸은 행이 없다. 본문은 일기와 같은 등급의 사용자 글이라
 * 로그에 남기지 않는다. 저장은 저장소의 upsert 가 맡는다(같은 칸을 동시에 고쳐도 한 줄로 모인다).
 */
@Entity
@Table(name = "weekly_happiness_entries")
public class WeeklyHappinessEntry extends BaseTimeEntity {

    public static final int CONTENT_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WeeklyHappinessCategory category;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    protected WeeklyHappinessEntry() {
    }

    /** 이 날짜가 속한 주의 일요일. */
    public static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    /** 그 순간(04:00 경계)이 속한 주의 일요일 — 토요일 밤의 주는 일요일 04:00 에 지난 주가 된다. */
    public static LocalDate weekStartAt(Instant at) {
        return weekStartOf(DayBoundary.toLocalDate(at));
    }

    /** 지금(04:00 경계)이 속한 주의 일요일. */
    public static LocalDate thisWeekStart() {
        return weekStartAt(Instant.now());
    }

    public static boolean isWeekStart(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public WeeklyHappinessCategory getCategory() {
        return category;
    }

    public String getContent() {
        return content;
    }
}
