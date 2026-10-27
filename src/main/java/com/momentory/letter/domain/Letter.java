package com.momentory.letter.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.momentory.common.persistence.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 바바의 편지 — 일기 한 벌에 한 통({@code uk_letters_diary}), 사용자의 하루에 한 통
 * ({@code uk_letters_user_date}). {@code letterDate} 는 일기가 속한 하루이고, 편지는 그다음 날
 * 04:00 부터 열린다.
 *
 * <p>행은 회고를 마친 트랜잭션에서 PENDING 으로 먼저 생긴다(네이티브 insert · on conflict do
 * nothing). GENERATING 으로 넘기는 일은 저장소의 조건부 갱신이 맡아 뒤에서 만들기와 열 때 보충
 * 중 한쪽만 들어오게 한다. 이 엔티티는 다 만든 내용을 채우는 일과 처음 연 시각만 바꾼다.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "letters")
public class Letter extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "diary_id", nullable = false)
    private Long diaryId;

    @Column(name = "letter_date", nullable = false)
    private LocalDate letterDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LetterStatus status;

    /** 작은 일 추천에 쓴 욕구 단어(CSV). */
    @Column(length = 200)
    private String needs;

    @Enumerated(EnumType.STRING)
    @Column(name = "needs_source", length = 20)
    private NeedsSource needsSource;

    @Column(length = 200)
    private String quote;

    @Column(columnDefinition = "TEXT")
    private String insight;

    @Column(name = "tonight_question", length = 200)
    private String tonightQuestion;

    @Column(nullable = false)
    private boolean stale;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "generation_started_at")
    private Instant generationStartedAt;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    protected Letter() {
    }

    /** 테스트와 시드용 — 운영 경로는 저장소의 {@code insertPending} 으로 행을 만든다. */
    public static Letter pending(Long userId, Long diaryId, LocalDate letterDate) {
        Letter letter = new Letter();
        letter.userId = Objects.requireNonNull(userId);
        letter.diaryId = Objects.requireNonNull(diaryId);
        letter.letterDate = Objects.requireNonNull(letterDate);
        letter.status = LetterStatus.PENDING;
        return letter;
    }

    /** 다 만든 내용을 채운다. 작은 일은 {@link LetterAction} 이 따로 담는다. */
    public void complete(LetterStatus status, String quote, String insight, String tonightQuestion,
            String needs, NeedsSource needsSource, String promptVersion, Instant now) {
        if (!status.isDone()) {
            throw new IllegalArgumentException("다 만든 상태만 채울 수 있습니다: " + status);
        }
        this.status = status;
        this.quote = quote;
        this.insight = Objects.requireNonNull(insight);
        this.tonightQuestion = tonightQuestion;
        this.needs = needs;
        this.needsSource = needsSource;
        this.promptVersion = promptVersion;
        this.generatedAt = now;
    }

    /** 뒤에서 만들기가 실패했다 — 열 때 다시 만든다. */
    public void fail() {
        this.status = LetterStatus.FAILED;
    }

    /** 처음 연 시각만 남긴다. 여러 번 불러도 처음 값이 그대로다. */
    public void markOpened(Instant now) {
        if (openedAt == null) {
            openedAt = now;
        }
    }

    /**
     * 그대로 보여 줘도 되는가 — 다 만들었고, 고친 일기로 다시 만들 일도 없다. 이미 열어 본 편지는
     * 고친 표시가 남아 있어도 다시 만들지 않는다(저장소의 {@code claim} 조건과 같다).
     */
    public boolean isShowable() {
        return status.isDone() && (!stale || openedAt != null);
    }

    /** {@code today}(04:00 경계) 기준으로 열 수 있는가 — 일기 다음 날부터 열린다. */
    public boolean isOpenableOn(LocalDate today) {
        return letterDate.isBefore(today);
    }

    /** 받은 날과 그다음 날 — 홈 아이콘에 점을 달 기간이다. */
    public boolean isNewOn(LocalDate today) {
        return isOpenableOn(today) && !letterDate.isBefore(today.minusDays(2));
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getDiaryId() {
        return diaryId;
    }

    public LocalDate getLetterDate() {
        return letterDate;
    }

    public LetterStatus getStatus() {
        return status;
    }

    public String getNeeds() {
        return needs;
    }

    public NeedsSource getNeedsSource() {
        return needsSource;
    }

    public String getQuote() {
        return quote;
    }

    public String getInsight() {
        return insight;
    }

    public String getTonightQuestion() {
        return tonightQuestion;
    }

    public boolean isStale() {
        return stale;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Instant getGenerationStartedAt() {
        return generationStartedAt;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
