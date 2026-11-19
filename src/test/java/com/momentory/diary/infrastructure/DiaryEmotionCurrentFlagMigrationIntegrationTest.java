package com.momentory.diary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiaryEmotionCurrentFlagMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @Test
    void recalculatesStoredFlagsFromCurrentEmotionsAndOriginalEmotions() {
        migrate("53");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long user = jdbc.queryForObject("INSERT INTO users (role) VALUES ('USER') RETURNING id", Long.class);
        String original = "{\"events\":[{\"emotions\":[\"JOY\"]}],\"unlinkedEmotions\":[\"SAD\"]}";
        long restored = diary(jdbc, user, original, original, true);
        long missing = diary(jdbc, user, original, "{\"events\":[],\"unlinkedEmotions\":[\"JOY\",\"CALM\"]}", false);
        long moved = diary(jdbc, user, original, "{\"events\":[{\"emotions\":[\"SAD\"]}],\"unlinkedEmotions\":[\"JOY\",\"CALM\"]}", true);
        long cleared = diary(jdbc, user, original, "{\"events\":[],\"unlinkedEmotions\":[]}", false);
        long noOriginalEmotions = diary(jdbc, user, "{}", "{\"unlinkedEmotions\":[\"JOY\"]}", true);
        Long noSnapshot = jdbc.queryForObject("INSERT INTO diaries (user_id, original) VALUES (?, '기록') RETURNING id", Long.class, user);
        migrate(null);
        assertThat(flag(jdbc, restored)).isFalse();
        assertThat(flag(jdbc, missing)).isTrue();
        assertThat(flag(jdbc, moved)).isFalse();
        assertThat(flag(jdbc, cleared)).isTrue();
        assertThat(flag(jdbc, noOriginalEmotions)).isFalse();
        assertThat(flag(jdbc, noSnapshot)).isFalse();
        assertThat(jdbc.queryForObject("SELECT original_emotion_selection_json FROM diaries WHERE id = ?", String.class, restored)).isEqualTo(original);
    }

    private long diary(JdbcTemplate jdbc, Long user, String original, String selected, boolean flag) {
        Long source = jdbc.queryForObject("INSERT INTO retrospects (user_id, status, state_json) VALUES (?, 'COMPLETED', '{}') RETURNING id", Long.class, user);
        return jdbc.queryForObject("INSERT INTO diaries (user_id, retrospect_id, original, original_emotion_selection_json, emotion_selection_json, emotion_classification_changed) VALUES (?, ?, '기록', ?, ?, ?) RETURNING id", Long.class, user, source, original, selected, flag);
    }

    private boolean flag(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT emotion_classification_changed FROM diaries WHERE id = ?", Boolean.class, id);
    }

    private void migrate(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        config.load().migrate();
    }
}
