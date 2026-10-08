package com.momentory.restrecord.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 쉼터 이용 기록 — 어떤 쉼터를 언제 했고, 끝까지 했는지, 마칠 때 기분이 어땠는지.
 *
 * <p>끝까지 한 기록에만 기분이 있다. 쉼터 안에서 쓴 글(걱정 원문 등)은 남기지 않는다.
 * 속한 하루({@code recordDate})는 남긴 순간의 {@link com.momentory.common.time.DayBoundary 04:00 경계} 날짜다.
 */
@Entity
@Table(name = "rest_records")
public class RestRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RestContent content;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(nullable = false)
    private boolean completed;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RestMood mood;

    protected RestRecord() {
    }

    private RestRecord(Long userId, RestContent content, LocalDate recordDate, boolean completed, RestMood mood) {
        this.userId = userId;
        this.content = content;
        this.recordDate = recordDate;
        this.completed = completed;
        this.mood = mood;
    }

    public static RestRecord create(Long userId, RestContent content, LocalDate recordDate, boolean completed, RestMood mood) {
        return new RestRecord(userId, content, recordDate, completed, mood);
    }

    public Long getId() {
        return id;
    }

    public RestContent getContent() {
        return content;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public boolean isCompleted() {
        return completed;
    }

    public RestMood getMood() {
        return mood;
    }
}
