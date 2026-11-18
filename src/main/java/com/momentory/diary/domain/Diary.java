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
 * 회고 한 벌이 남기는 일기 — 이전엔 {@code retrospects} 의 {@code diary} 컬럼이었다. 조회가 잦고
 * (그날의 일기 화면) 월별 조회도 붙을 예정이라, 진행 상태(회고)와 떼어 독립 테이블로 뽑았다
 * ({@link ActionCard} 와 같은 결). <b>회고 한 벌에 일기 한 벌</b>(테이블의 {@code uk_diaries_retrospect}).
 *
 * <p>일기 본문은 {@code original} 하나다(채팅흐름_v2 에서 리프레임 본문을 없앴다). 대표 감정
 * {@code primaryEmotion}(리포트용, 감정 없이 끝난 일기면 null)을 함께 두어 월별 조회에서 바로 쓴다.
 * 생성 시기는 {@link BaseTimeEntity#getCreatedAt()}.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "diaries")
public class Diary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id")
    private Long retrospectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String original;

    // v2: 대표 감정 — 리포트(일별/주간)가 쓴다. 감정 없이 끝난 일기(탐색 미진행·추출 없음)면 null.
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_emotion", length = 30)
    private Emotion primaryEmotion;

    /** v2 감정 태그(CSV 키) — 일기에서 드러난 감정 전체. 라벨은 읽는 쪽이 Emotion 에서 역참조. */
    @Column(columnDefinition = "TEXT")
    private String emotions;

    @Column(length = 80)
    private String weather;

    @Column(name = "events_json", columnDefinition = "TEXT")
    private String eventsJson;

    @Column(name = "original_emotion_selection_json", columnDefinition = "TEXT")
    private String originalEmotionSelectionJson;

    @Column(name = "emotion_selection_json", columnDefinition = "TEXT")
    private String emotionSelectionJson;

    @Column(name = "emotion_classification_changed", nullable = false)
    private boolean emotionClassificationChanged;

    public String getOriginalEmotionSelectionJson() { return originalEmotionSelectionJson; }
    public String getEmotionSelectionJson() { return emotionSelectionJson; }
    public boolean isEmotionClassificationChanged() { return emotionClassificationChanged; }

    /** 원본 분류는 최초 수정 전에 한 번만 남긴다. 추가만 한 수정은 변경 지표에 넣지 않는다. */
    public void updateEmotions(DiaryEmotionSelection original, String originalJson,
            DiaryEmotionSelection selected, String selectedJson) {
        if (originalEmotionSelectionJson == null) originalEmotionSelectionJson = Objects.requireNonNull(originalJson);
        emotionSelectionJson = Objects.requireNonNull(selectedJson);
        var all = selected.allEmotions();
        if (!all.contains(primaryEmotion)) primaryEmotion = all.isEmpty() ? null : all.getFirst();
        emotions = all.isEmpty() ? null : String.join(",", all.stream().map(Emotion::key).toList());
        emotionClassificationChanged = retrospectId != null && selected.removesFrom(original);
    }

    public String getEventsJson() {
        return eventsJson;
    }

    /** 직접 쓴 본문과 같은 트랜잭션에서 그 본문의 사건을 교체한다. */
    public void replaceEvents(String eventsJson) {
        if (retrospectId != null) throw new IllegalStateException("chat diary events belong to retrospect");
        this.eventsJson = Objects.requireNonNull(eventsJson);
        // 직접 작성 본문이 바뀌면 사건도 새 추출 결과를 따른다. 현재 감정 태그는 유지하며
        // 새 사건에 근거가 없는 감정은 조회에서 사건 없는 감정으로 돌린다. 채팅 원본에는 적용하지 않는다.
        this.emotionSelectionJson = null;
        this.originalEmotionSelectionJson = null;
    }

    public String getWeather() {
        return weather;
    }

    protected Diary() {
    }

    private Diary(Long userId, Long retrospectId, String original, Emotion primaryEmotion,
            String emotions) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.retrospectId = retrospectId;
        this.original = Objects.requireNonNull(original, "original must not be null");
        this.primaryEmotion = primaryEmotion;
        this.emotions = emotions;
    }

    public static Diary create(Long userId, Long retrospectId, String original,
            Emotion primaryEmotion, String emotions) {
        return new Diary(userId, Objects.requireNonNull(retrospectId), original, primaryEmotion, emotions);
    }

    /** 직접 쓴 일기는 대화 세션이나 추론한 감정을 만들지 않는다. */
    public static Diary write(Long userId, String original, Emotion emotion) {
        return new Diary(userId, null, original, emotion, emotion == null ? null : emotion.key());
    }

    /**
     * 「내가 남긴 오늘」 본문을 사용자가 고친 것으로 바꾼다 — 회고 완료 화면(C6)의 직접 고치기.
     *
     * <p>대표 감정·회고 연결은 그대로 둔다 — 고칠 수 있는 것은 본문뿐이다.
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

    public Emotion getPrimaryEmotion() {
        return primaryEmotion;
    }

    /** v2 감정 태그 CSV(없으면 null) — 읽는 쪽이 Emotion 으로 역참조. */
    public String getEmotions() {
        return emotions;
    }
}
