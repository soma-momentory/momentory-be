package com.momentory.retrospecttopic.domain;

import java.util.Objects;

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
 * 토픽 하나에 매칭된 감정 하나 — {@link RetrospectTopic} 아래의 값. 토픽과 감정은 N:N 이라(한 토픽에
 * 감정 여럿, 한 감정이 여러 토픽) 쌍마다 한 행으로 남긴다. 같은 토픽에 같은 감정 중복은 테이블의
 * {@code uk_rte_topic_emotion} 이 막는다. 부모({@code topic_id})는 {@link RetrospectTopic} 이 관리한다.
 */
@Entity
@Table(name = "retrospect_topic_emotions")
public class RetrospectTopicEmotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Emotion emotion;

    protected RetrospectTopicEmotion() {
    }

    RetrospectTopicEmotion(Emotion emotion) {
        this.emotion = Objects.requireNonNull(emotion, "emotion must not be null");
    }

    public Long getId() {
        return id;
    }

    public Emotion getEmotion() {
        return emotion;
    }
}
