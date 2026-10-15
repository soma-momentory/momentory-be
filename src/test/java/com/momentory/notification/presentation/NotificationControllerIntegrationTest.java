package com.momentory.notification.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.user.withdrawal.application.UserDeletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class NotificationControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccessTokenIssuer tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserDeletionService deletion;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void appliesJoinedAtExpiryAndCriticalDefaultReadToListAndUnread() throws Exception {
        User user = userJoinedDaysAgo(120);
        Instant now = Instant.now();
        insert("NOTICE", "before", now.minusSeconds(121L * 86400), null, "ALL", null);
        insert("NEWS", "fresh", now.minusSeconds(86400), null, "ALL", null);
        insert("NOTICE", "within 90 days", now.minusSeconds(89L * 86400), null, "ALL", null);
        insert("NOTICE", "old", now.minusSeconds(91L * 86400), null, "ALL", null);
        insert("CRITICAL_NOTICE", "important before", now.minusSeconds(125L * 86400), now.plusSeconds(86400), "ALL", null);
        insert("CRITICAL_NOTICE", "expired", now.minusSeconds(125L * 86400), now.minusSeconds(86400), "ALL", null);

        String before = mvc.perform(authed(get("/api/v1/notifications"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].title").value("fresh"))
                .andExpect(jsonPath("$.items[0].readAt").isEmpty())
                .andExpect(jsonPath("$.items[1].title").value("within 90 days"))
                .andExpect(jsonPath("$.items[2].title").value("important before"))
                .andExpect(jsonPath("$.items[2].readAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String defaultReadAt = new tools.jackson.databind.ObjectMapper().readTree(before)
                .path("items").get(2).path("readAt").stringValue();
        Long importantBefore = jdbc.queryForObject("SELECT id FROM notifications WHERE title = 'important before'", Long.class);
        mvc.perform(authed(patch("/api/v1/notifications/{id}/read", importantBefore), user))
                .andExpect(status().isNoContent());
        String after = mvc.perform(authed(get("/api/v1/notifications"), user))
                .andReturn().getResponse().getContentAsString();
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(after)
                .path("items").get(2).path("readAt").stringValue()).isEqualTo(defaultReadAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE notification_id = ?", Integer.class, importantBefore)).isZero();
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasUnread").value(true));

        Long fresh = jdbc.queryForObject("SELECT id FROM notifications WHERE title = 'fresh'", Long.class);
        mvc.perform(authed(patch("/api/v1/notifications/{id}/read", fresh), user))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        Long within90Days = jdbc.queryForObject("SELECT id FROM notifications WHERE title = 'within 90 days'", Long.class);
        mvc.perform(authed(patch("/api/v1/notifications/{id}/read", within90Days), user))
                .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(jsonPath("$.hasUnread").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?", Integer.class, user.getId())).isEqualTo(2);
    }

    @Test
    void paginatesStableTiesAndSynchronizesReadAcrossDevices() throws Exception {
        User user = userJoinedDaysAgo(5);
        Instant published = Instant.now().minusSeconds(3600);
        Long first = insert("NOTICE", "first", published, null, "ALL", null);
        Long second = insert("NEWS", "second", published, null, "ALL", null);
        String tokenA = tokens.issueAccessToken(user.getId(), user.getRole());
        String tokenB = tokens.issueAccessToken(user.getId(), user.getRole());

        String page = mvc.perform(get("/api/v1/notifications?size=1").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor = new tools.jackson.databind.ObjectMapper().readTree(page).path("nextCursor").stringValue();
        mvc.perform(get("/api/v1/notifications").param("size", "1").param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mvc.perform(patch("/api/v1/notifications/{id}/read", second).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        Instant firstReadAt = jdbc.queryForObject("SELECT read_at FROM notification_reads WHERE user_id = ? AND notification_id = ?",
                Timestamp.class, user.getId(), second).toInstant();
        mvc.perform(patch("/api/v1/notifications/{id}/read", second).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_reads WHERE user_id = ? AND notification_id = ?",
                Timestamp.class, user.getId(), second).toInstant()).isEqualTo(firstReadAt);
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.items[0].readAt").isNotEmpty());
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(jsonPath("$.hasUnread").value(true));
        mvc.perform(authed(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + first + "\",\"" + second + "\"]}"), user))
                .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(jsonPath("$.hasUnread").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?", Integer.class, user.getId())).isEqualTo(2);
    }

    @Test
    void rejectsInvisibleSingleAndSkipsInvisibleBulkWithoutChangingOtherUsers() throws Exception {
        User user = userJoinedDaysAgo(10);
        User other = userJoinedDaysAgo(10);
        Long privateNotice = insert("NOTICE", "private", Instant.now().minusSeconds(3600), null, "USER", other.getId());
        Long visible = insert("NOTICE", "visible", Instant.now().minusSeconds(3600), null, "ALL", null);

        mvc.perform(authed(patch("/api/v1/notifications/{id}/read", privateNotice), user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(authed(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + privateNotice + "\",\"" + visible + "\"]}"), user))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ? AND notification_id = ?", Integer.class, user.getId(), privateNotice)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ? AND notification_id = ?", Integer.class, user.getId(), visible)).isEqualTo(1);
        mvc.perform(authed(get("/api/v1/notifications/unread"), other))
                .andExpect(jsonPath("$.hasUnread").value(true));
    }

    @Test
    void criticalNoticePublishedAfterJoiningStartsUnreadAndKeepsItsOwnExpiry() throws Exception {
        User user = userJoinedDaysAgo(120);
        Instant now = Instant.now();
        Long active = insert("CRITICAL_NOTICE", "active critical", now.minusSeconds(100L * 86400),
                now.plusSeconds(86400), "ALL", null);
        insert("CRITICAL_NOTICE", "expired critical", now.minusSeconds(100L * 86400),
                now.minusSeconds(86400), "ALL", null);

        mvc.perform(authed(get("/api/v1/notifications"), user))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].readAt").isEmpty());
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(jsonPath("$.hasUnread").value(true));
        mvc.perform(authed(patch("/api/v1/notifications/{id}/read", active), user))
                .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/notifications/unread"), user))
                .andExpect(jsonPath("$.hasUnread").value(false));
    }

    @Test
    void rejectsInvalidInputsAndUnauthenticatedCallsWithoutReads() throws Exception {
        User user = userJoinedDaysAgo(10);
        Long id = insert("NOTICE", "visible", Instant.now().minusSeconds(3600), null, "ALL", null);
        mvc.perform(authed(get("/api/v1/notifications").param("cursor", "invalid"), user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(authed(get("/api/v1/notifications").param("size", "51"), user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(authed(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"), user))
                .andExpect(status().isBadRequest());
        String tooManyIds = String.join(",", Collections.nCopies(51, "\"" + id + "\""));
        mvc.perform(authed(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + tooManyIds + "]}"), user))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\",\"bad\"]}"), user))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/notifications/{id}/read", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"]}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads", Integer.class)).isZero();
    }

    @Test
    void userDeletionCascadesReadRecordsAndPrivateNotifications() {
        User user = userJoinedDaysAgo(10);
        User other = userJoinedDaysAgo(10);
        Long personal = insert("NOTICE", "personal", Instant.now().minusSeconds(3600), null, "USER", user.getId());
        Long shared = insert("NEWS", "shared", Instant.now().minusSeconds(3600), null, "ALL", null);
        jdbc.update("INSERT INTO notification_reads(user_id, notification_id, read_at) VALUES (?, ?, now()), (?, ?, now())",
                user.getId(), shared, other.getId(), shared);

        deletion.delete(user.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?", Integer.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, personal)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?", Integer.class, other.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, shared)).isEqualTo(1);
    }

    @Test
    void bulkReadRollsBackEarlierRowsWhenAWriteFails() {
        User user = userJoinedDaysAgo(10);
        Long first = insert("NOTICE", "first", Instant.now().minusSeconds(3600), null, "ALL", null);
        Long second = insert("NEWS", "second", Instant.now().minusSeconds(3600), null, "ALL", null);
        jdbc.execute("""
                CREATE FUNCTION fail_notification_read() RETURNS trigger AS $$
                BEGIN
                    IF NEW.notification_id = %d THEN
                        RAISE EXCEPTION 'forced notification read failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(second));
        jdbc.execute("""
                CREATE TRIGGER fail_notification_read_trigger
                BEFORE INSERT ON notification_reads
                FOR EACH ROW EXECUTE FUNCTION fail_notification_read()
                """);
        try {
            assertThatThrownBy(() -> mvc.perform(authed(post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ids\":[\"" + first + "\",\"" + second + "\"]}"), user)))
                    .hasStackTraceContaining("forced notification read failure");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_reads WHERE user_id = ?", Integer.class, user.getId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_notification_read_trigger ON notification_reads");
            jdbc.execute("DROP FUNCTION fail_notification_read()");
        }
    }

    private User userJoinedDaysAgo(int days) {
        User user = users.saveAndFlush(User.create());
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(days * 86400L)), user.getId());
        return user;
    }

    private Long insert(String type, String title, Instant published, Instant expires, String audience, Long userId) {
        return jdbc.queryForObject("""
                        INSERT INTO notifications(type, title, body, link, audience, user_id, published_at, expires_at)
                        VALUES (?, ?, '본문', '/my', ?, ?, ?, ?) RETURNING id
                        """, Long.class, type, title, audience, userId, Timestamp.from(published),
                expires == null ? null : Timestamp.from(expires));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + tokens.issueAccessToken(user.getId(), user.getRole()));
    }
}
