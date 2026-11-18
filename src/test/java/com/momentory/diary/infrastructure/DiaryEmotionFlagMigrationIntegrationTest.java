package com.momentory.diary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiaryEmotionFlagMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17"));

    @Test
    void preservesExistingChangesAndReplacesTheTimestampWithANonNullFlag() {
        migrate("52");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long user = jdbc.queryForObject("INSERT INTO users (role) VALUES ('USER') RETURNING id", Long.class);
        Long source = jdbc.queryForObject("INSERT INTO retrospects (user_id, status, state_json) VALUES (?, 'COMPLETED', '{}') RETURNING id", Long.class, user);
        Long changed = jdbc.queryForObject("""
                INSERT INTO diaries (user_id, retrospect_id, original, original_emotion_selection_json,
                emotion_selection_json, emotion_classification_changed_at) VALUES (?, ?, '기록', '{}', '{}', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, user, source);
        Long untouched = jdbc.queryForObject("INSERT INTO diaries (user_id, original) VALUES (?, '기록') RETURNING id", Long.class, user);

        migrate("53");

        assertThat(jdbc.queryForObject("SELECT emotion_classification_changed FROM diaries WHERE id = ?", Boolean.class, changed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT emotion_classification_changed FROM diaries WHERE id = ?", Boolean.class, untouched)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'diaries' AND column_name = 'emotion_classification_changed_at'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT original_emotion_selection_json FROM diaries WHERE id = ?", String.class, changed)).isEqualTo("{}");
        Long fresh = jdbc.queryForObject("INSERT INTO diaries (user_id, original) VALUES (?, '새 기록') RETURNING id", Long.class, user);
        assertThat(jdbc.queryForObject("SELECT emotion_classification_changed FROM diaries WHERE id = ?", Boolean.class, fresh)).isFalse();
        assertThatThrownBy(() -> jdbc.update("UPDATE diaries SET emotion_classification_changed = NULL WHERE id = ?", fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE diaries SET emotion_classification_changed = TRUE WHERE id = ?", fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrate(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        config.load().migrate();
    }
}
