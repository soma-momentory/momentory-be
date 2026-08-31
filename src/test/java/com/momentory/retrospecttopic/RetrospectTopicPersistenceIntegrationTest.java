package com.momentory.retrospecttopic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.retrospect.application.RetrospectCompleted;
import com.momentory.retrospect.application.RetrospectCompleted.TopicData;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.TopicType;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospecttopic.domain.RetrospectTopic;
import com.momentory.retrospecttopic.infrastructure.RetrospectTopicRepository;
import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 회고 토픽(주 일정·키워드 + 매칭 감정) 저장 경로 통합 검증 — 실제 Postgres(+ Flyway V22)로
 * 이벤트 → 리스너 → 저장, 그리고 마이그레이션이 건 FK/cascade/SET NULL 을 확인한다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class RetrospectTopicPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired RetrospectTopicRepository topicRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM retrospect_topic_emotions");
        jdbcTemplate.update("DELETE FROM retrospect_topics");
        retrospectRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM schedules");
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("완료 이벤트의 토픽이 저장된다 — 주 일정(감정 2개)·키워드(감정 1개), 감정은 쌍마다 한 행")
    void topicsFromEventArePersisted() {
        User user = userRepository.saveAndFlush(User.create());
        Retrospect retro = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, "{}"));
        Schedule schedule = scheduleRepository.saveAndFlush(
                Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 31), "면접 스터디", 0));

        eventPublisher.publishEvent(new RetrospectCompleted(retro.getId(), user.getId(), null, null,
                List.of(
                        new TopicData(TopicType.SCHEDULE, schedule.getId(), "면접 스터디",
                                List.of(Emotion.ANXIOUS, Emotion.PROUD)),
                        new TopicData(TopicType.KEYWORD, null, "취업", List.of(Emotion.ANXIOUS)))));

        List<RetrospectTopic> topics = topicRepository.findByRetrospectId(retro.getId());
        assertThat(topics).hasSize(2);

        RetrospectTopic scheduleTopic = topics.stream()
                .filter(t -> t.getTopicType() == TopicType.SCHEDULE).findFirst().orElseThrow();
        assertThat(scheduleTopic.getScheduleId()).isEqualTo(schedule.getId());
        assertThat(scheduleTopic.getLabel()).isEqualTo("면접 스터디");
        assertThat(scheduleTopic.getCreatedDate()).isNotNull();

        RetrospectTopic keywordTopic = topics.stream()
                .filter(t -> t.getTopicType() == TopicType.KEYWORD).findFirst().orElseThrow();
        assertThat(keywordTopic.getScheduleId()).isNull();
        assertThat(keywordTopic.getLabel()).isEqualTo("취업");

        // 감정은 (토픽 × 감정) 한 쌍이 한 행 — 주 일정 2 + 키워드 1 = 3.
        Long emotionRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrospect_topic_emotions", Long.class);
        assertThat(emotionRows).isEqualTo(3);
    }

    @Test
    @DisplayName("사용자 삭제가 토픽과 감정까지 cascade 로 정리한다")
    void deletingUserCascadesTopicsAndEmotions() {
        User user = userRepository.saveAndFlush(User.create());
        Retrospect retro = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, "{}"));
        topicRepository.saveAndFlush(RetrospectTopic.keyword(user.getId(), retro.getId(), "취업",
                LocalDate.of(2026, 8, 31), List.of(Emotion.ANXIOUS, Emotion.STUCK)));

        // 회고를 먼저 지워 retrospects FK 를 풀고, 사용자를 native 로 지워 DB cascade 를 관찰한다.
        retrospectRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM retrospect_topics", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrospect_topic_emotions", Long.class)).isZero();
    }

    @Test
    @DisplayName("주 일정이 삭제되면 schedule_id 는 NULL 로 풀리고 토픽(label)은 살아남는다")
    void deletingScheduleSetsTopicScheduleIdNull() {
        User user = userRepository.saveAndFlush(User.create());
        Retrospect retro = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, "{}"));
        Schedule schedule = scheduleRepository.saveAndFlush(
                Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 31), "면접 스터디", 0));
        RetrospectTopic topic = topicRepository.saveAndFlush(RetrospectTopic.schedule(user.getId(),
                retro.getId(), schedule.getId(), "면접 스터디", LocalDate.of(2026, 8, 31),
                List.of(Emotion.ANXIOUS)));

        jdbcTemplate.update("DELETE FROM schedules WHERE id = ?", schedule.getId());

        Long scheduleId = jdbcTemplate.queryForObject(
                "SELECT schedule_id FROM retrospect_topics WHERE id = ?", Long.class, topic.getId());
        assertThat(scheduleId).isNull();
        String label = jdbcTemplate.queryForObject(
                "SELECT label FROM retrospect_topics WHERE id = ?", String.class, topic.getId());
        assertThat(label).isEqualTo("면접 스터디");
    }
}
