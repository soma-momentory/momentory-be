package com.momentory.user.withdrawal.presentation;

import com.momentory.auth.kakao.infrastructure.KakaoApiErrorCode;
import com.momentory.auth.kakao.infrastructure.KakaoApiException;
import com.momentory.auth.apple.infrastructure.AppleRevokeClient;
import com.momentory.auth.kakao.infrastructure.KakaoUnlinkClient;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.auth.token.application.RefreshTokenIssuer;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.user.withdrawal.application.UserDeletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
@Import({
        UserWithdrawalControllerIntegrationTest.KakaoUnlinkClientTestConfiguration.class,
        UserWithdrawalControllerIntegrationTest.AppleRevokeClientTestConfiguration.class
})
class UserWithdrawalControllerIntegrationTest {

    private static final List<String> USER_OWNED_TABLES = List.of(
            "oauth_accounts",
            "refresh_tokens",
            "user_profiles",
            "user_interest_areas",
            "user_rest_methods",
            "schedules",
            "daily_memos",
            "retrospects",
            "action_cards",
            "diaries"
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
    );

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenIssuer refreshTokenIssuer;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired UserDeletionService userDeletionService;
    @Autowired KakaoUnlinkClient kakaoUnlinkClient;
    @Autowired AppleRevokeClient appleRevokeClient;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        reset(kakaoUnlinkClient, appleRevokeClient);
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void withdrawsCurrentUserAndDeletesOnlyTheirEntireDataGraph() throws Exception {
        UserFixture target = createUserFixture("target");
        UserFixture other = createUserFixture("other");
        String accessToken = accessTokenIssuer.issueAccessToken(target.userId(), target.user().getRole());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(kakaoUnlinkClient).unlink(target.providerUserId());

        assertThat(count("users", target.userId())).isZero();
        for (String table : USER_OWNED_TABLES) {
            assertThat(count(table, target.userId())).as(table + " target rows").isZero();
            assertThat(count(table, other.userId())).as(table + " other rows").isEqualTo(1);
        }

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(target.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        mockMvc.perform(delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsUnauthenticatedWithdrawalWithoutDeletingData() throws Exception {
        UserFixture target = createUserFixture("unauthenticated");

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        assertThat(count("users", target.userId())).isEqualTo(1);
        for (String table : USER_OWNED_TABLES) {
            assertThat(count(table, target.userId())).as(table).isEqualTo(1);
        }
    }

    @Test
    void rollsBackEntireWithdrawalWhenAChildDeletionFails() {
        UserFixture target = createUserFixture("rollback");
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_diary_delete() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced diary deletion failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_diary_delete_trigger
                BEFORE DELETE ON diaries
                FOR EACH ROW EXECUTE FUNCTION fail_diary_delete()
                """);

        try {
            assertThatThrownBy(() -> userDeletionService.delete(target.userId()))
                    .isInstanceOf(RuntimeException.class);

            assertThat(count("users", target.userId())).isEqualTo(1);
            for (String table : USER_OWNED_TABLES) {
                assertThat(count(table, target.userId())).as(table).isEqualTo(1);
            }
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_diary_delete_trigger ON diaries");
            jdbcTemplate.execute("DROP FUNCTION fail_diary_delete()");
        }
    }

    @Test
    void keepsInternalDataWhenKakaoUnlinkFails() throws Exception {
        UserFixture target = createUserFixture("unlink-failure");
        String accessToken = accessTokenIssuer.issueAccessToken(target.userId(), target.user().getRole());
        doThrow(new KakaoApiException(
                KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR,
                "Kakao unlink API could not be reached."
        )).when(kakaoUnlinkClient).unlink(target.providerUserId());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KAKAO_API_NETWORK_ERROR"));

        assertThat(count("users", target.userId())).isEqualTo(1);
        for (String table : USER_OWNED_TABLES) {
            assertThat(count(table, target.userId())).as(table).isEqualTo(1);
        }
    }

    private UserFixture createUserFixture(String key) {
        User user = userRepository.saveAndFlush(User.create());
        Long userId = user.getId();
        String uniqueKey = key + "-" + UUID.randomUUID();
        String refreshToken = "refresh-" + uniqueKey;

        jdbcTemplate.update("""
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id)
                VALUES (?, 'KAKAO', ?)
                """, userId, uniqueKey);
        refreshTokenRepository.saveAndFlush(RefreshToken.create(
                user,
                refreshTokenIssuer.hash(refreshToken),
                Instant.now().plusSeconds(3600)
        ));
        jdbcTemplate.update("""
                INSERT INTO user_profiles (
                    user_id, nickname, gender, reflection_time, time_zone,
                    calendar_integration_enabled, notification_enabled
                ) VALUES (?, ?, 'FEMALE', '21:30', 'Asia/Seoul', TRUE, TRUE)
                """, userId, "테스터");
        jdbcTemplate.update("INSERT INTO user_interest_areas (user_id, interest_area) VALUES (?, 'SELF')", userId);
        jdbcTemplate.update("INSERT INTO user_rest_methods (user_id, rest_method) VALUES (?, 'READING')", userId);

        Long scheduleId = jdbcTemplate.queryForObject("""
                INSERT INTO schedules (user_id, schedule_date, title, display_order, source)
                VALUES (?, CURRENT_DATE, ?, 1, 'MANUAL')
                RETURNING id
                """, Long.class, userId, key + " schedule");
        jdbcTemplate.update("""
                INSERT INTO daily_memos (user_id, memo_date, content)
                VALUES (?, CURRENT_DATE, ?)
                """, userId, key + " memo");
        Long retrospectId = jdbcTemplate.queryForObject("""
                INSERT INTO retrospects (
                    user_id, status, schedule_id, current_emotion, state_json
                ) VALUES (?, 'COMPLETED', ?, 'CALM', '{}')
                RETURNING id
                """, Long.class, userId, scheduleId);
        jdbcTemplate.update("""
                INSERT INTO action_cards (
                    user_id, retrospect_id, situation, target_action, created_date
                ) VALUES (?, ?, ?, ?, CURRENT_DATE)
                """, userId, retrospectId, key + " situation", key + " action");
        jdbcTemplate.update("""
                INSERT INTO diaries (
                    user_id, retrospect_id, original, primary_emotion
                ) VALUES (?, ?, ?, 'CALM')
                """, userId, retrospectId, key + " original");

        return new UserFixture(user, refreshToken, uniqueKey);
    }

    private long count(String table, Long userId) {
        String idColumn = table.equals("users") ? "id" : "user_id";
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }

    @Test
    void revokesAppleLinkWithTheStoredRefreshToken() throws Exception {
        AppleFixture target = createAppleUserFixture("apple-target");
        String accessToken = accessTokenIssuer.issueAccessToken(
                target.fixture().userId(), target.fixture().user().getRole());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // 계정이 사라지기 전에 보관해 둔 토큰으로 끊어야 한다 — 지운 뒤에는 알 수 없다
        verify(appleRevokeClient).revoke(target.appleRefreshToken());
        assertThat(count("users", target.fixture().userId())).isZero();
    }

    @Test
    void keepsInternalDataWhenAppleRevokeFails() throws Exception {
        AppleFixture target = createAppleUserFixture("apple-fail");
        String accessToken = accessTokenIssuer.issueAccessToken(
                target.fixture().userId(), target.fixture().user().getRole());
        doThrow(new com.momentory.auth.apple.infrastructure.AppleApiException(
                com.momentory.auth.apple.infrastructure.AppleApiErrorCode.APPLE_API_NETWORK_ERROR,
                "unreachable"))
                .when(appleRevokeClient).revoke(target.appleRefreshToken());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().is5xxServerError());

        // 연결을 못 끊었으면 계정도 남아야 한다 — 지워 버리면 되돌릴 방법이 없다
        assertThat(count("users", target.fixture().userId())).isEqualTo(1);
    }

    /** 애플로 가입한 사용자 — 카카오 행 대신 애플 행과 보관된 refresh token 을 둔다 */
    private AppleFixture createAppleUserFixture(String key) {
        UserFixture fixture = createUserFixture(key);
        String appleRefreshToken = "apple-refresh-" + fixture.providerUserId();
        jdbcTemplate.update("DELETE FROM oauth_accounts WHERE user_id = ?", fixture.userId());
        jdbcTemplate.update("""
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id, apple_refresh_token)
                VALUES (?, 'APPLE', ?, ?)
                """, fixture.userId(), fixture.providerUserId(), appleRefreshToken);
        return new AppleFixture(fixture, appleRefreshToken);
    }

    private record AppleFixture(UserFixture fixture, String appleRefreshToken) {
    }

    private record UserFixture(User user, String refreshToken, String providerUserId) {
        Long userId() {
            return user.getId();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AppleRevokeClientTestConfiguration {

        @Bean
        @Primary
        AppleRevokeClient mockAppleRevokeClient() {
            return org.mockito.Mockito.mock(AppleRevokeClient.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KakaoUnlinkClientTestConfiguration {

        @Bean
        @Primary
        KakaoUnlinkClient mockKakaoUnlinkClient() {
            return org.mockito.Mockito.mock(KakaoUnlinkClient.class);
        }
    }
}
