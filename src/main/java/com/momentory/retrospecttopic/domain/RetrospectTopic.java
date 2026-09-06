package com.momentory.retrospecttopic.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.TopicType;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 회고 채팅에서 뽑은 토픽 하나 — 주 일정 또는 키워드 (채팅흐름_v2). 채팅이 끝난 뒤 주 일정·키워드를
 * 뽑아 각 토픽에 감정을 매칭한 결과를 남긴다. 회고 한 벌이 토픽을 여러 개(주 일정 1~2 + 키워드 N) 낳고,
 * 토픽 하나에 감정이 0~여러 개 붙는다.
 *
 * <p>주 일정은 일정 목록에서 온 것({@code scheduleId} 있음)과 채팅에서 뽑은 자유 텍스트({@code null})
 * 둘 다다. {@code label} 은 어느 쪽이든 항상 텍스트를 든다 — 키워드 누적 집계가 일정 삭제와 무관하게
 * 살아남는다. {@code createdDate} 는 주간 리포트가 기간으로 자르는 축이다(04:00 하루 경계).
 *
 * <p><b>이 토픽이 감정 목록의 애그리거트 루트다.</b> 감정은 루트를 통해서만 더하고, 저장은 함께 흐른다
 * ({@code cascade = ALL}, {@code orphanRemoval}). 같은 토픽 안 중복 감정은 팩토리에서 걸러 낸다.
 */
@Entity
@Table(name = "retrospect_topics")
public class RetrospectTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic_type", nullable = false, length = 20)
    private TopicType topicType;

    /** 목록에서 온 주 일정이면 그 일정 id, 자유 텍스트 주 일정·키워드면 null. */
    @Column(name = "schedule_id")
    private Long scheduleId;

    /** 일정 제목 상한(255, {@code Schedule.TITLE_MAX_LENGTH})과 맞춘다. 그 이상은 팩토리가 자른다. */
    static final int LABEL_MAX_LENGTH = 255;

    @Column(nullable = false, length = LABEL_MAX_LENGTH)
    private String label;

    @Column(name = "created_date", nullable = false)
    private LocalDate createdDate;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "topic_id", nullable = false)
    private List<RetrospectTopicEmotion> emotions = new ArrayList<>();

    protected RetrospectTopic() {
    }

    private RetrospectTopic(Long userId, Long retrospectId, TopicType topicType, Long scheduleId,
            String label, LocalDate createdDate, List<Emotion> emotions) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.retrospectId = Objects.requireNonNull(retrospectId, "retrospectId must not be null");
        this.topicType = Objects.requireNonNull(topicType, "topicType must not be null");
        this.scheduleId = scheduleId;
        this.label = requireLabel(label);
        this.createdDate = Objects.requireNonNull(createdDate, "createdDate must not be null");
        for (Emotion e : distinct(emotions)) {
            this.emotions.add(new RetrospectTopicEmotion(e));
        }
    }

    /** 주 일정 토픽 — 목록 일정이면 {@code scheduleId} 를 주고, 자유 텍스트면 null 을 준다. */
    public static RetrospectTopic schedule(Long userId, Long retrospectId, Long scheduleId,
            String label, LocalDate createdDate, List<Emotion> emotions) {
        return new RetrospectTopic(userId, retrospectId, TopicType.SCHEDULE, scheduleId, label,
                createdDate, emotions);
    }

    /** 키워드 토픽 — 일정과 무관한 텍스트라 {@code scheduleId} 는 항상 null 이다. */
    public static RetrospectTopic keyword(Long userId, Long retrospectId, String label,
            LocalDate createdDate, List<Emotion> emotions) {
        return new RetrospectTopic(userId, retrospectId, TopicType.KEYWORD, null, label, createdDate,
                emotions);
    }

    private static String requireLabel(String label) {
        Objects.requireNonNull(label, "label must not be null");
        String trimmed = label.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        // 키워드는 추출 결과라 길이를 장담할 수 없다 — 상한을 넘으면 잘라, 완료 트랜잭션이 라벨
        // 하나 때문에 롤백되지 않게 한다(컬럼과 같은 상한).
        return trimmed.length() > LABEL_MAX_LENGTH ? trimmed.substring(0, LABEL_MAX_LENGTH) : trimmed;
    }

    /** 같은 토픽 안 중복 감정 제거(입력 순서 유지) — 테이블의 uk_rte_topic_emotion 위반을 막는다. */
    private static List<Emotion> distinct(List<Emotion> emotions) {
        if (emotions == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(emotions));
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

    public TopicType getTopicType() {
        return topicType;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public String getLabel() {
        return label;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    /** 매칭된 감정들 — 방어적 복사(내부 컬렉션을 노출하지 않는다). */
    public List<Emotion> getEmotions() {
        return emotions.stream().map(RetrospectTopicEmotion::getEmotion).toList();
    }
}
