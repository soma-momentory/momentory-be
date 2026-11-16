package com.momentory.retrospect.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V46 — 감정 10종으로 저장된 평문 값을 대표 감정 12종으로 옮긴다.
 * V45 까지 올린 DB 에 옛 값을 심고, 끝까지 올린 뒤 컬럼별 결과를 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EmotionTwelveMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17"));

    @Test
    @DisplayName("일기·바람 카드·토픽 감정의 옛 값을 새 값으로 옮기고, 합쳐져 겹친 것은 하나만 남긴다")
    void migratesLegacyEmotionValues() {
        migrate("45");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (role, onboarding_completed) VALUES ('USER', TRUE) RETURNING id",
                Long.class);

        // 일기 — 대표 감정은 상수 이름(대문자), 태그는 키 CSV(소문자)
        Long mixed = diary(jdbc, userId, "FRUSTRATED", "frustrated,angry,tired,calm,lethargic,happy");
        Long plain = diary(jdbc, userId, "DEPRESSED", "depressed, stuck");
        Long untouched = diary(jdbc, userId, "ANXIOUS", "anxious,proud");
        Long empty = diary(jdbc, userId, null, null);
        Long tired = diary(jdbc, userId, "TIRED", "tired");
        Long lethargic = diary(jdbc, userId, "LETHARGIC", "lethargic");
        Long happy = diary(jdbc, userId, "HAPPY", "happy");
        Long stuck = diary(jdbc, userId, "STUCK", "stuck");

        // 바람 카드 — 키 CSV(소문자)
        Long cardMerged = card(jdbc, userId, "angry,frustrated");
        Long cardPlain = card(jdbc, userId, "tired");
        Long cardUntouched = card(jdbc, userId, "calm");
        Long cardEmpty = card(jdbc, userId, null);

        // 토픽 × 감정 — 상수 이름(대문자). 옮기면 겹치는 쌍이 유니크 제약에 걸리지 않아야 한다.
        Long topicMerged = topic(jdbc, userId, "야근");
        topicEmotions(jdbc, topicMerged, "TIRED", "LETHARGIC", "ANGRY", "FRUSTRATED", "HAPPY");
        Long topicPlain = topic(jdbc, userId, "면접");
        topicEmotions(jdbc, topicPlain, "DEPRESSED", "STUCK", "PROUD");
        // 다른 토픽의 같은 감정은 서로 지우지 않는다.
        Long topicOther = topic(jdbc, userId, "산책");
        topicEmotions(jdbc, topicOther, "TIRED");

        migrate(null);

        assertThat(diaryRow(jdbc, mixed)).containsExactly("ANGRY", "angry,exhausted,calm,joy");
        assertThat(diaryRow(jdbc, plain)).containsExactly("SAD", "sad,confused");
        assertThat(diaryRow(jdbc, untouched)).containsExactly("ANXIOUS", "anxious,proud");
        assertThat(diaryRow(jdbc, empty)).containsExactly(null, null);
        assertThat(diaryRow(jdbc, tired)).containsExactly("EXHAUSTED", "exhausted");
        assertThat(diaryRow(jdbc, lethargic)).containsExactly("EXHAUSTED", "exhausted");
        assertThat(diaryRow(jdbc, happy)).containsExactly("JOY", "joy");
        assertThat(diaryRow(jdbc, stuck)).containsExactly("CONFUSED", "confused");

        assertThat(cardEmotions(jdbc, cardMerged)).isEqualTo("angry");
        assertThat(cardEmotions(jdbc, cardPlain)).isEqualTo("exhausted");
        assertThat(cardEmotions(jdbc, cardUntouched)).isEqualTo("calm");
        assertThat(cardEmotions(jdbc, cardEmpty)).isNull();

        assertThat(emotionsOfTopic(jdbc, topicMerged))
                .containsExactlyInAnyOrder("EXHAUSTED", "ANGRY", "JOY");
        assertThat(emotionsOfTopic(jdbc, topicPlain))
                .containsExactlyInAnyOrder("SAD", "CONFUSED", "PROUD");
        assertThat(emotionsOfTopic(jdbc, topicOther)).containsExactly("EXHAUSTED");

        // 옛 값이 어느 컬럼에도 남지 않는다.
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM diaries
                         WHERE primary_emotion IN ('HAPPY','DEPRESSED','FRUSTRATED','STUCK','LETHARGIC','TIRED')
                            OR emotions ~ '(happy|depressed|frustrated|stuck|lethargic|tired)')
                     + (SELECT count(*) FROM action_cards
                         WHERE emotions ~ '(happy|depressed|frustrated|stuck|lethargic|tired)')
                     + (SELECT count(*) FROM retrospect_topic_emotions
                         WHERE emotion IN ('HAPPY','DEPRESSED','FRUSTRATED','STUCK','LETHARGIC','TIRED'))
                """, Long.class)).isZero();
    }

    private static void migrate(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        config.load().migrate();
    }

    private static Long retrospect(JdbcTemplate jdbc, Long userId) {
        return jdbc.queryForObject(
                "INSERT INTO retrospects (user_id, status, state_json) VALUES (?, 'COMPLETED', '{}') RETURNING id",
                Long.class, userId);
    }

    private static Long diary(JdbcTemplate jdbc, Long userId, String primary, String emotions) {
        return jdbc.queryForObject("""
                INSERT INTO diaries (user_id, retrospect_id, original, primary_emotion, emotions)
                VALUES (?, ?, '오늘의 일기', ?, ?) RETURNING id
                """, Long.class, userId, retrospect(jdbc, userId), primary, emotions);
    }

    private static Long card(JdbcTemplate jdbc, Long userId, String emotions) {
        return jdbc.queryForObject("""
                INSERT INTO action_cards (user_id, retrospect_id, situation, created_date, emotions)
                VALUES (?, ?, '상황', CURRENT_DATE, ?) RETURNING id
                """, Long.class, userId, retrospect(jdbc, userId), emotions);
    }

    private static Long topic(JdbcTemplate jdbc, Long userId, String label) {
        return jdbc.queryForObject("""
                INSERT INTO retrospect_topics (user_id, retrospect_id, topic_type, label, created_date)
                VALUES (?, ?, 'KEYWORD', ?, CURRENT_DATE) RETURNING id
                """, Long.class, userId, retrospect(jdbc, userId), label);
    }

    private static void topicEmotions(JdbcTemplate jdbc, Long topicId, String... emotions) {
        for (String emotion : emotions) {
            jdbc.update("INSERT INTO retrospect_topic_emotions (topic_id, emotion) VALUES (?, ?)",
                    topicId, emotion);
        }
    }

    private static List<String> diaryRow(JdbcTemplate jdbc, Long id) {
        return jdbc.queryForObject("SELECT primary_emotion, emotions FROM diaries WHERE id = ?",
                (rs, n) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2)), id);
    }

    private static String cardEmotions(JdbcTemplate jdbc, Long id) {
        return jdbc.queryForObject("SELECT emotions FROM action_cards WHERE id = ?", String.class, id);
    }

    private static List<String> emotionsOfTopic(JdbcTemplate jdbc, Long topicId) {
        return jdbc.queryForList(
                "SELECT emotion FROM retrospect_topic_emotions WHERE topic_id = ?", String.class, topicId);
    }
}
