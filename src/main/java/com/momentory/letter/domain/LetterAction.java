package com.momentory.letter.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 편지에 실린 작은 일 한 칸(0~2). {@code title} 은 그날 화면에 보인 승인 제목 그대로라 연결표가
 * 바뀌어도 남는다. 한 편지에서 {@code chosen} 은 하나뿐이다({@code uk_letter_actions_chosen}).
 * 그날 밤 회고 답({@code result})은 회고 연결 작업(BE PR 2)이 채운다.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "letter_actions")
public class LetterAction {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final int WHEN_LABEL_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "letter_id", nullable = false)
    private Long letterId;

    @Column(nullable = false)
    private short position;

    @Column(name = "action_id", length = 10)
    private String actionId;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LetterActionSource source;

    @Column(name = "when_label", length = WHEN_LABEL_MAX_LENGTH)
    private String whenLabel;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(nullable = false)
    private boolean chosen;

    @Column(name = "remind_at")
    private Instant remindAt;

    @Column(length = 20)
    private String result;

    @Column(name = "result_at")
    private Instant resultAt;

    protected LetterAction() {
    }

    public static LetterAction create(Long letterId, int position, String actionId, String title,
            LetterActionSource source, boolean chosen) {
        LetterAction action = new LetterAction();
        action.letterId = Objects.requireNonNull(letterId);
        action.position = (short) position;
        action.actionId = actionId;
        action.title = Objects.requireNonNull(title);
        action.source = Objects.requireNonNull(source);
        action.chosen = chosen;
        return action;
    }

    /** 이 일을 고르고 「언제」를 남긴다. 다른 칸의 선택은 먼저 풀어야 한다(부분 유일 인덱스). */
    public void choose(String whenLabel, Long scheduleId, Instant remindAt) {
        this.chosen = true;
        this.whenLabel = whenLabel;
        this.scheduleId = scheduleId;
        this.remindAt = remindAt;
    }

    public void unchoose() {
        this.chosen = false;
        this.whenLabel = null;
        this.scheduleId = null;
        this.remindAt = null;
    }

    /** 그날 밤 회고 첫 질문의 답을 남긴다(DONE · NOT_DONE · SKIPPED). */
    public void recordResult(String result, Instant now) {
        this.result = Objects.requireNonNull(result);
        this.resultAt = Objects.requireNonNull(now);
    }

    public boolean isTried() {
        return "DONE".equals(result);
    }

    public Long getId() {
        return id;
    }

    public Long getLetterId() {
        return letterId;
    }

    public int getPosition() {
        return position;
    }

    public String getActionId() {
        return actionId;
    }

    public String getTitle() {
        return title;
    }

    public LetterActionSource getSource() {
        return source;
    }

    public String getWhenLabel() {
        return whenLabel;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public boolean isChosen() {
        return chosen;
    }

    public Instant getRemindAt() {
        return remindAt;
    }

    public String getResult() {
        return result;
    }

    public Instant getResultAt() {
        return resultAt;
    }
}
