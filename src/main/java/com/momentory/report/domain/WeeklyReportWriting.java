package com.momentory.report.domain;

import java.time.LocalDate;

import com.momentory.common.persistence.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 한 주의 AI 글 — 표지 문장 · 바바가 본 한 주 · 좋은 말 · 공통점 문장. 사용자당 주마다 한 벌이다.
 * {@code sourceDigest} 는 글을 쓸 때 본 일기의 지문이라, 일기를 고치거나 지우면 다음에 열 때 다시 쓴다.
 */
@Entity
@Table(name = "weekly_report_writings")
public class WeeklyReportWriting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "cover_line", nullable = false)
    private String coverLine;

    @Column(columnDefinition = "TEXT")
    private String review;

    @Column(name = "good_words", columnDefinition = "TEXT")
    private String goodWords;

    @Column(name = "ok_line")
    private String okLine;

    @Column(name = "source_digest", nullable = false, length = 64)
    private String sourceDigest;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    protected WeeklyReportWriting() {
    }

    public static WeeklyReportWriting create(Long userId, LocalDate weekStart) {
        WeeklyReportWriting writing = new WeeklyReportWriting();
        writing.userId = userId;
        writing.weekStart = weekStart;
        return writing;
    }

    public void write(String coverLine, String review, String goodWords, String okLine, String sourceDigest,
            String promptVersion) {
        this.coverLine = coverLine;
        this.review = review;
        this.goodWords = goodWords;
        this.okLine = okLine;
        this.sourceDigest = sourceDigest;
        this.promptVersion = promptVersion;
    }

    public String getCoverLine() {
        return coverLine;
    }

    public String getReview() {
        return review;
    }

    public String getGoodWords() {
        return goodWords;
    }

    public String getOkLine() {
        return okLine;
    }

    public String getSourceDigest() {
        return sourceDigest;
    }
}
