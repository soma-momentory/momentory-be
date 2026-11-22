package com.momentory.retrospect.infrastructure.persistence;

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
class DispositionStartMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17"));

    @Test
    void preservesLegacyArmsAndSignalsAndBackfillsOnlyPreviouslyAppliedTreatment() {
        migrate("54");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Long user = jdbc.queryForObject("insert into users (role) values ('USER') returning id", Long.class);
        for (String arm : new String[] {"TREATMENT", "CONTROL", "NONE"}) {
            Long session = jdbc.queryForObject("insert into retrospects (user_id, status, state_json)"
                    + " values (?, 'COMPLETED', '{}') returning id", Long.class, user);
            jdbc.update("""
                    insert into retrospect_disposition_observations (user_id, retrospect_id, observed_turns,
                        resistance_count, listen_request_count, other_view_request_count, feeling_question_count,
                        feeling_expressed_count, feeling_avoided_count, awaiting_feeling_answer,
                        min_confidence, policy_version, apply_arm, derived_self_led, derived_other_view,
                        created_at, updated_at)
                    values (?, ?, 1, 1, 0, 0, 0, 0, 0, false, 0.5, 'v14', ?, true, false, now(), now())
                    """, user, session, arm);
        }
        migrate("55");

        assertThat(jdbc.queryForList("select apply_arm from retrospect_disposition_observations order by id", String.class))
                .containsExactly("TREATMENT", "CONTROL", "NONE");
        assertThat(jdbc.queryForList("select resistance_count from retrospect_disposition_observations", Integer.class))
                .containsOnly(1);
        assertThat(jdbc.queryForList("select derived_self_led from retrospect_disposition_observations", Boolean.class))
                .containsOnly(true);
        assertThat(jdbc.queryForList("select disposition_version from retrospect_disposition_observations", String.class))
                .containsOnly("conditional-v1");
        assertThat(jdbc.queryForList("select past_disposition_applied from retrospect_disposition_observations order by id",
                Boolean.class)).containsExactly(true, false, false);
        assertThat(jdbc.queryForList("select derived_history_sessions from retrospect_disposition_observations", Integer.class))
                .containsOnly(0);
        assertThatThrownBy(() -> jdbc.update("update retrospect_disposition_observations set derived_history_sessions = -1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update retrospect_disposition_observations set disposition_version = null"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from users where id = ?", user);
        assertThat(jdbc.queryForObject("select count(*) from retrospect_disposition_observations", Integer.class)).isZero();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load().migrate();
    }
}
