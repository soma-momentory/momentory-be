package com.momentory.user.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OnboardingVersionMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
    );

    @Test
    void marksUsersWhoCompletedOnboardingAsLegacyVersionDuringV30Upgrade() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("29"))
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        Long completedUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (role, onboarding_completed) VALUES ('USER', TRUE) RETURNING id", Long.class);
        Long newUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (role, onboarding_completed) VALUES ('USER', FALSE) RETURNING id", Long.class);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String versionQuery = "SELECT onboarding_version FROM users WHERE id = ?";
        assertThat(jdbcTemplate.queryForObject(versionQuery, Integer.class, completedUserId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(versionQuery, Integer.class, newUserId)).isZero();
    }
}
