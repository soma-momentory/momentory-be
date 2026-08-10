package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

import com.momentory.common.persistence.BaseTimeEntity;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.script.RetroMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회고 세션의 영속 스냅샷.
 *
 * <p>도메인 애그리거트는 순수 {@code RetrospectState} 이고, 이 엔티티는 그 진행 상태를 통째로
 * {@code state_json} 에 담아 보관하는 얇은 래퍼다(스냅샷 방식). 목록·조회·수명주기에 필요한
 * 값만 별도 컬럼으로 뽑아 둔다. 사용자는 스칼라 {@code userId} 로 참조한다(be 관례).
 */
@Entity
@Table(name = "retrospects")
public class Retrospect extends BaseTimeEntity {

    private static final int SCHEDULE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RetrospectStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RetroMode mode;

    @Column(length = SCHEDULE_MAX_LENGTH)
    private String schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_emotion", length = 30)
    private Emotion scheduleEmotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_emotion", nullable = false, length = 30)
    private Emotion currentEmotion;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(columnDefinition = "TEXT")
    private String diary;

    @Column(name = "reframed_diary", columnDefinition = "TEXT")
    private String reframedDiary;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Retrospect() {
    }

    private Retrospect(Long userId, RetrospectStatus status, RetroMode mode, String schedule,
            Emotion scheduleEmotion, Emotion currentEmotion, String stateJson) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.currentEmotion = Objects.requireNonNull(currentEmotion, "currentEmotion must not be null");
        this.stateJson = Objects.requireNonNull(stateJson, "stateJson must not be null");
        this.mode = mode;
        this.schedule = schedule;
        this.scheduleEmotion = scheduleEmotion;
    }

    public static Retrospect start(Long userId, RetrospectStatus status, RetroMode mode,
            String schedule, Emotion scheduleEmotion, Emotion currentEmotion, String stateJson) {
        return new Retrospect(userId, status, mode, schedule, scheduleEmotion, currentEmotion,
                stateJson);
    }

    /**
     * 한 턴 처리 후 진행 상태를 반영한다. 완료 시에만 일기·완료시각이 채워진다.
     */
    public void sync(RetrospectStatus status, RetroMode mode, String stateJson,
            String diary, String reframedDiary, Instant completedAt) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.stateJson = Objects.requireNonNull(stateJson, "stateJson must not be null");
        this.mode = mode;
        if (diary != null) {
            this.diary = diary;
        }
        if (reframedDiary != null) {
            this.reframedDiary = reframedDiary;
        }
        if (completedAt != null) {
            this.completedAt = completedAt;
        }
    }

    public boolean belongsTo(Long userId) {
        return this.userId.equals(userId);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public RetrospectStatus getStatus() {
        return status;
    }

    public RetroMode getMode() {
        return mode;
    }

    public String getSchedule() {
        return schedule;
    }

    public Emotion getScheduleEmotion() {
        return scheduleEmotion;
    }

    public Emotion getCurrentEmotion() {
        return currentEmotion;
    }

    public String getStateJson() {
        return stateJson;
    }

    public String getDiary() {
        return diary;
    }

    public String getReframedDiary() {
        return reframedDiary;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
