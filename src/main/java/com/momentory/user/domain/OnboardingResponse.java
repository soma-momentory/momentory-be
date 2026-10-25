package com.momentory.user.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 온보딩 질문에 고른 답. 마이페이지에서 고치는 현재 설정(프로필)과 달리 그 판을 할 때의 응답 기록이라
 * 따로 둔다. 판마다 한 행이고, 같은 판을 다시 하면 덮어쓴다. 건너뛴 질문은 {@code null} 이다.
 */
@Entity
@Table(name = "user_onboarding_responses")
public class OnboardingResponse extends BaseTimeEntity {

    @EmbeddedId
    private Id id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mood_change", length = 30)
    private MoodChange moodChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "know_why", length = 30)
    private KnowWhy knowWhy;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_end", length = 30)
    private DayEnd dayEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "diary_history", length = 30)
    private DiaryHistory diaryHistory;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_onboarding_goals",
            joinColumns = {
                    @JoinColumn(name = "user_id", referencedColumnName = "user_id"),
                    @JoinColumn(name = "version", referencedColumnName = "version")
            },
            foreignKey = @ForeignKey(name = "fk_user_onboarding_goals_response")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "goal", nullable = false, length = 30)
    private Set<Goal> goals = new HashSet<>();

    protected OnboardingResponse() {
    }

    public OnboardingResponse(Long userId, int version) {
        this.id = new Id(Objects.requireNonNull(userId), version);
    }

    public void answer(MoodChange moodChange, KnowWhy knowWhy, DayEnd dayEnd, DiaryHistory diaryHistory, Set<Goal> goals) {
        this.moodChange = moodChange;
        this.knowWhy = knowWhy;
        this.dayEnd = dayEnd;
        this.diaryHistory = diaryHistory;
        this.goals.clear();
        this.goals.addAll(Objects.requireNonNull(goals));
    }

    public Id getId() {
        return id;
    }

    public MoodChange getMoodChange() {
        return moodChange;
    }

    public KnowWhy getKnowWhy() {
        return knowWhy;
    }

    public DayEnd getDayEnd() {
        return dayEnd;
    }

    public DiaryHistory getDiaryHistory() {
        return diaryHistory;
    }

    public Set<Goal> getGoals() {
        return Set.copyOf(goals);
    }

    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "version", nullable = false)
        private int version;

        protected Id() {
        }

        public Id(Long userId, int version) {
            this.userId = userId;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other && Objects.equals(userId, other.userId) && version == other.version;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, version);
        }
    }

    /** 하루에 기분이 몇 번쯤 바뀌나요? */
    public enum MoodChange { RARELY, FEW_TIMES, VERY_OFTEN }

    /** 내 기분이 왜 그런지, 잘 아는 편인가요? */
    public enum KnowWhy { MOSTLY, SOMETIMES, NOT_SURE }

    /** 하루가 끝나면, 주로 어떤 편인가요? 알림 기본 시각을 고르는 데도 쓴다. */
    public enum DayEnd { TIRED_SLEEP, OVERTHINK, OKAY }

    /** 일기를 써 본 적 있나요? */
    public enum DiaryHistory { REGULARLY, STOPPED, RARELY }

    /** 바바와 뭘 해 보고 싶어요? (여러 개) */
    public enum Goal { KNOW_WHEN_TIRED, FIND_TIRED_REASON, WRAPUP_HABIT, DIARY_HABIT, REST_ON_HARD_DAYS }
}
