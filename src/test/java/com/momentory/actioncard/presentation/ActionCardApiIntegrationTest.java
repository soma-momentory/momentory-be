package com.momentory.actioncard.presentation;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 행동 카드 보관함 조회 API 통합 검증 — 실제 HTTP + 인증 + DB 로 월별 목록·단건을 본다.
 *
 * <p>월 경계·정렬을 보려면 {@code created_at} 을 마음대로 심어야 하는데, 엔티티는 {@code @PrePersist}
 * 로 생성 시각을 now 로 박으므로 여기선 JdbcTemplate 로 직접 넣는다(FK 를 위해 회고는 실제로 저장).
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class ActionCardApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired ActionCardRepository actionCardRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
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
        actionCardRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("월별 목록 — KST 기준 그 달 카드만 최신순으로, 상황·목표행동·날짜와 함께 온다")
    void monthlyListReturnsThatMonthNewestFirst() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        // 8월(KST · 04:00 하루 경계) — 셋. 하나는 8/1 04:00 KST 경계(포함).
        seedCard(user, "8월 늦은 상황", "8월 늦은 행동",
                Instant.parse("2026-08-20T10:00:00Z"));
        seedCard(user, "8월 중간 상황", "8월 중간 행동",
                Instant.parse("2026-08-14T02:23:47Z"));
        seedCard(user, "8월 경계 포함", "8월 경계 행동",
                Instant.parse("2026-07-31T19:00:00Z")); // = 2026-08-01T04:00 KST (하루 경계)
        // 7월(KST) 경계 제외 — 2026-08-01 03:59:59 KST 는 4시 전이라 아직 7/31 이다.
        seedCard(user, "7월 경계 제외", "7월 행동",
                Instant.parse("2026-07-31T18:59:59Z"));
        // 9월(KST) 제외.
        seedCard(user, "9월 상황", "9월 행동",
                Instant.parse("2026-09-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/action-cards")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionCards.length()").value(3))
                // 최신순: 8/20 → 8/14 → 8/1(경계)
                .andExpect(jsonPath("$.actionCards[0].situation").value("8월 늦은 상황"))
                .andExpect(jsonPath("$.actionCards[0].targetAction").value("8월 늦은 행동"))
                .andExpect(jsonPath("$.actionCards[0].done").value(false))
                .andExpect(jsonPath("$.actionCards[0].createdDate").exists())
                .andExpect(jsonPath("$.actionCards[0].createdAt").exists())
                .andExpect(jsonPath("$.actionCards[0].retrospectId").exists())
                // 아직 안 해본 카드는 doneAt/reflection 필드가 빠진다(NON_NULL)
                .andExpect(jsonPath("$.actionCards[0].doneAt").doesNotExist())
                .andExpect(jsonPath("$.actionCards[0].reflection").doesNotExist())
                .andExpect(jsonPath("$.actionCards[1].situation").value("8월 중간 상황"))
                .andExpect(jsonPath("$.actionCards[2].situation").value("8월 경계 포함"));
    }

    @Test
    @DisplayName("월별 목록 — 남의 카드는 섞이지 않는다")
    void monthlyListIsScopedToOwner() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        seedCard(owner, "내 8월 상황", "내 행동", Instant.parse("2026-08-10T01:00:00Z"));
        seedCard(other, "남의 8월 상황", "남의 행동", Instant.parse("2026-08-11T01:00:00Z"));

        mockMvc.perform(get("/api/v1/action-cards")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionCards.length()").value(1))
                .andExpect(jsonPath("$.actionCards[0].situation").value("내 8월 상황"));
    }

    @Test
    @DisplayName("단건 조회 — 상황·목표행동·상태를 돌려준다")
    void getOneReturnsCard() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "단건 상황", "단건 행동",
                Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(get("/api/v1/action-cards/{id}", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) cardId))
                .andExpect(jsonPath("$.situation").value("단건 상황"))
                .andExpect(jsonPath("$.targetAction").value("단건 행동"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.createdDate").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.retrospectId").exists());
    }

    @Test
    @DisplayName("단건 조회 — 남의 카드는 404")
    void getOneOfAnotherUserIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(owner, "남의 상황", "남의 행동",
                Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(get("/api/v1/action-cards/{id}", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTION_CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("월별 목록 — 잘못된 월(13)은 400")
    void invalidMonthIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/action-cards")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("year", "2026")
                        .param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증 없으면 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/action-cards").param("year", "2026").param("month", "8"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("해봤어요 — done·doneAt 이 DB 에 남고 응답에도 온다")
    void markDonePersists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) cardId))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.doneAt").exists())
                .andExpect(jsonPath("$.reflection").doesNotExist());

        assertCard(cardId, true, true, null);
    }

    @Test
    @DisplayName("느낀 점 — 완료 상태에서 남고, 다시 보내면 덮어쓴다")
    void reflectionPersistsWhenDone() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true,\"reflection\":\"해보니 괜찮았다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reflection").value("해보니 괜찮았다"));

        assertCard(cardId, true, true, "해보니 괜찮았다");
    }

    @Test
    @DisplayName("되돌리기 — done·doneAt·느낀 점이 함께 비워진다")
    void undoClearsDoneAndReflection() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        // 먼저 해보고 느낀 점까지 남긴 뒤 되돌린다
        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true,\"reflection\":\"한 줄\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.doneAt").doesNotExist())
                .andExpect(jsonPath("$.reflection").doesNotExist());

        assertCard(cardId, false, false, null);
    }

    @Test
    @DisplayName("되돌린 상태의 느낀 점은 400 — 상태를 바꾸지 않는다")
    void reflectionWhileUndoneIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":false,\"reflection\":\"남길 수 없다\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertCard(cardId, false, false, null);
    }

    @Test
    @DisplayName("완료 여부 누락은 400")
    void missingDoneIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("남의 카드는 404 — 상태를 바꾸지 못한다")
    void completingAnotherUsersCardIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(owner, "상황", "행동", Instant.parse("2026-08-10T01:00:00Z"));

        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTION_CARD_NOT_FOUND"));

        assertCard(cardId, false, false, null);
    }

    @Test
    @DisplayName("완료 반영은 인증이 필요하다 — 없으면 401")
    void completionRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/action-cards/{id}/completion", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("삭제 — 그 카드 한 장만 지운다")
    void deleteRemovesCard() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(user, "상황", "행동", Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(delete("/api/v1/action-cards/{id}", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM action_cards WHERE id = ?", Integer.class, cardId);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    @DisplayName("삭제 — 남의 카드는 404, 지워지지 않는다")
    void deletingAnotherUsersCardIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long cardId = seedCard(owner, "상황", "행동", Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(delete("/api/v1/action-cards/{id}", cardId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTION_CARD_NOT_FOUND"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM action_cards WHERE id = ?", Integer.class, cardId);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    @DisplayName("삭제 — 없는 카드는 404")
    void deletingMissingCardIs404() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(delete("/api/v1/action-cards/{id}", 999_999)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTION_CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제는 인증이 필요하다 — 없으면 401")
    void deleteRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/action-cards/{id}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** DB 의 카드 상태를 확인한다 — 응답만이 아니라 실제로 남았는지 본다. */
    private void assertCard(long cardId, boolean done, boolean hasDoneAt, String reflection) {
        Boolean actualDone = jdbcTemplate.queryForObject(
                "SELECT done FROM action_cards WHERE id = ?", Boolean.class, cardId);
        Integer doneAtCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM action_cards WHERE id = ? AND done_at IS NOT NULL",
                Integer.class, cardId);
        String actualReflection = jdbcTemplate.queryForObject(
                "SELECT reflection FROM action_cards WHERE id = ?", String.class, cardId);
        org.junit.jupiter.api.Assertions.assertEquals(done, actualDone);
        org.junit.jupiter.api.Assertions.assertEquals(hasDoneAt ? 1 : 0, doneAtCount);
        org.junit.jupiter.api.Assertions.assertEquals(reflection, actualReflection);
    }

    /** 회고(FK 대상)를 실제 저장하고, 그에 딸린 행동 카드를 지정한 {@code createdAt} 으로 직접 넣는다. */
    private long seedCard(User user, String situation, String targetAction, Instant createdAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, "{}"));
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        LocalDate createdDate = createdAt.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        jdbcTemplate.update("""
                INSERT INTO action_cards (user_id, retrospect_id, situation, target_action,
                                          created_date, from_rest_preference, done,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospect.getId(), situation, targetAction, createdDate, false,
                false, at, at);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM action_cards WHERE retrospect_id = ?", Long.class,
                retrospect.getId());
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
