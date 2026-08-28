package com.momentory.diary.domain;

import java.util.Objects;

import com.momentory.common.persistence.BaseTimeEntity;
import com.momentory.retrospect.domain.Emotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회고 한 벌이 남기는 일기 — 이전엔 {@code retrospects} 의 {@code diary}/{@code reframed_diary}
 * 컬럼이었다. 조회가 잦고(그날의 일기 화면) 월별 조회도 붙을 예정이라, 진행 상태(회고)와 떼어
 * 독립 테이블로 뽑았다({@link ActionCard} 와 같은 결). <b>회고 한 벌에 일기 한 벌</b>
 * (테이블의 {@code uk_diaries_retrospect}).
 *
 * <p>{@code reframed} 는 방향에 따라 없을 수 있다(짧은 기록형은 원본만 남는다). 진입 감정 두 종을
 * 함께 두어 월별 조회에서 바로 쓴다: {@code currentEmotion}(필수), {@code scheduleEmotion}(특정
 * 일정 없는 '오늘 하루' 회고면 null). 생성 시기는 {@link BaseTimeEntity#getCreatedAt()}.
 */
@Entity
@Table(name = "diaries")
public class Diary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String original;

    @Column(columnDefinition = "TEXT")
    private String reframed;

    // v2: 대표 감정 — 리포트(일별/주간)가 쓴다. 감정 없이 끝난 일기(탐색 미진행·추출 없음)면 null.
    @Enumerated(EnumType.STRING)
    @Column(name = "current_emotion", length = 30)
    private Emotion currentEmotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_emotion", length = 30)
    private Emotion scheduleEmotion;

    /** v2 감정 태그(CSV 키) — 일기에서 드러난 감정 전체. 라벨은 읽는 쪽이 Emotion 에서 역참조. */
    @Column(columnDefinition = "TEXT")
    private String emotions;

    protected Diary() {
    }

    private Diary(Long userId, Long retrospectId, String original, String reframed,
            Emotion currentEmotion, Emotion scheduleEmotion, String emotions) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.retrospectId = Objects.requireNonNull(retrospectId, "retrospectId must not be null");
        this.original = Objects.requireNonNull(original, "original must not be null");
        this.currentEmotion = currentEmotion;
        this.reframed = reframed;
        this.scheduleEmotion = scheduleEmotion;
        this.emotions = emotions;
    }

    public static Diary create(Long userId, Long retrospectId, String original, String reframed,
            Emotion currentEmotion, Emotion scheduleEmotion, String emotions) {
        return new Diary(userId, retrospectId, original, reframed, currentEmotion, scheduleEmotion,
                emotions);
    }

    /**
     * 「내가 남긴 오늘」 본문을 사용자가 고친 것으로 바꾼다 — 회고 완료 화면(C6)의 직접 고치기.
     *
     * <p>바바가 다시 본 오늘({@code reframed})·진입 감정·회고 연결은 그대로 둔다 — 고칠 수 있는
     * 것은 원본 본문뿐이다({@code A4.md}: "고칠 수 있는 것은 body 와 감정 태그뿐"이고 감정 태그는
     * 목록용이라 서버 진입 감정과 다르다).
     */
    public void updateOriginal(String original) {
        this.original = Objects.requireNonNull(original, "original must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRetrospectId() {
        return retrospectId;
    }

    public String getOriginal() {
        return original;
    }

    public String getReframed() {
        return reframed;
    }

    public Emotion getCurrentEmotion() {
        return currentEmotion;
    }

    public Emotion getScheduleEmotion() {
        return scheduleEmotion;
    }

    /** v2 감정 태그 CSV(없으면 null) — 읽는 쪽이 Emotion 으로 역참조. */
    public String getEmotions() {
        return emotions;
    }
}
