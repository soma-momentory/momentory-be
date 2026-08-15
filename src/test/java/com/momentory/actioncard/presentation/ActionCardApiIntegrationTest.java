package com.momentory.actioncard.presentation;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

        // 8월(KST) — 셋. 하나는 8/1 00:00 KST 경계(포함).
        seedCard(user, "8월 늦은 상황", "8월 늦은 행동",
                Instant.parse("2026-08-20T10:00:00Z"));
        seedCard(user, "8월 중간 상황", "8월 중간 행동",
                Instant.parse("2026-08-14T02:23:47Z"));
        seedCard(user, "8월 경계 포함", "8월 경계 행동",
                Instant.parse("2026-07-31T15:00:00Z")); // = 2026-08-01T00:00 KST
        // 7월(KST) 경계 제외 — 2026-07-31 23:59:59 KST.
        seedCard(user, "7월 경계 제외", "7월 행동",
                Instant.parse("2026-07-31T14:59:59Z"));
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

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** 회고(FK 대상)를 실제 저장하고, 그에 딸린 행동 카드를 지정한 {@code createdAt} 으로 직접 넣는다. */
    private long seedCard(User user, String situation, String targetAction, Instant createdAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, Emotion.DEPRESSED, "{}"));
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
