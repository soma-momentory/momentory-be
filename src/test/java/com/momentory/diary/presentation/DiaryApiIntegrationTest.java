package com.momentory.diary.presentation;

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
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 일기 보관함 조회 API 통합 검증 — 실제 HTTP + 인증 + DB 로 월별 목록·단건을 본다.
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
class DiaryApiIntegrationTest {

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
    @Autowired DiaryRepository diaryRepository;
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
        // 삭제 테스트가 회고에 딸린 행동 카드를 심으므로, 회고를 지우기 전에 먼저 비운다(FK).
        jdbcTemplate.update("DELETE FROM action_cards");
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("월별 목록 — KST 기준 그 달 일기만 최신순으로, 전체 본문·감정·작성시각과 함께 온다")
    void monthlyListReturnsThatMonthNewestFirst() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        // 8월(KST) — 셋. 하나는 8/1 00:00 KST 경계(포함).
        seedDiary(user, "8월 늦은 일기", "8월 늦은 리프레임", Emotion.DEPRESSED, Emotion.ANXIOUS,
                Instant.parse("2026-08-20T10:00:00Z"));
        seedDiary(user, "8월 중간 일기", "8월 중간 리프레임", Emotion.ANGRY, Emotion.STUCK,
                Instant.parse("2026-08-14T02:23:47Z"));
        seedDiary(user, "8월 경계 포함", null, Emotion.CALM, null,
                Instant.parse("2026-07-31T15:00:00Z")); // = 2026-08-01T00:00 KST
        // 7월(KST) 경계 제외 — 2026-07-31 23:59:59 KST.
        seedDiary(user, "7월 경계 제외", null, Emotion.TIRED, null,
                Instant.parse("2026-07-31T14:59:59Z"));
        // 9월(KST) 제외.
        seedDiary(user, "9월 일기", null, Emotion.PROUD, null,
                Instant.parse("2026-09-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(3))
                // 최신순: 8/20 → 8/14 → 8/1(경계)
                .andExpect(jsonPath("$.diaries[0].original").value("8월 늦은 일기"))
                .andExpect(jsonPath("$.diaries[0].reframed").value("8월 늦은 리프레임"))
                .andExpect(jsonPath("$.diaries[0].currentEmotion").value("depressed"))
                .andExpect(jsonPath("$.diaries[0].scheduleEmotion").value("anxious"))
                .andExpect(jsonPath("$.diaries[0].createdAt").exists())
                .andExpect(jsonPath("$.diaries[0].retrospectId").exists())
                .andExpect(jsonPath("$.diaries[1].original").value("8월 중간 일기"))
                .andExpect(jsonPath("$.diaries[2].original").value("8월 경계 포함"))
                // 일정 감정이 없으면 필드 자체가 빠진다(NON_NULL)
                .andExpect(jsonPath("$.diaries[2].scheduleEmotion").doesNotExist())
                .andExpect(jsonPath("$.diaries[2].reframed").doesNotExist());
    }

    @Test
    @DisplayName("월별 목록 — 남의 일기는 섞이지 않는다")
    void monthlyListIsScopedToOwner() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        seedDiary(owner, "내 8월 일기", null, Emotion.DEPRESSED, Emotion.ANXIOUS,
                Instant.parse("2026-08-10T01:00:00Z"));
        seedDiary(other, "남의 8월 일기", null, Emotion.HAPPY, null,
                Instant.parse("2026-08-11T01:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(1))
                .andExpect(jsonPath("$.diaries[0].original").value("내 8월 일기"));
    }

    @Test
    @DisplayName("단건 조회 — 전체 본문을 돌려준다")
    void getOneReturnsFullBody() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "본문 전체", "리프레임 전체", Emotion.DEPRESSED, Emotion.ANXIOUS,
                Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries/{id}", diaryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) diaryId))
                .andExpect(jsonPath("$.original").value("본문 전체"))
                .andExpect(jsonPath("$.reframed").value("리프레임 전체"))
                .andExpect(jsonPath("$.currentEmotion").value("depressed"))
                .andExpect(jsonPath("$.scheduleEmotion").value("anxious"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("단건 조회 — 남의 일기는 404")
    void getOneOfAnotherUserIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(owner, "남의 일기", null, Emotion.CALM, null,
                Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries/{id}", diaryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));
    }

    @Test
    @DisplayName("월별 목록 — 잘못된 월(13)은 400")
    void invalidMonthIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("year", "2026")
                        .param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증 없으면 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/diaries").param("year", "2026").param("month", "8"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("삭제 — 일기만 지운다. 행동 카드와 회고 세션은 남는다")
    void deleteRemovesOnlyDiary() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "지울 일기", null, Emotion.CALM, null,
                Instant.parse("2026-08-14T02:00:00Z"));
        long retrospectId = jdbcTemplate.queryForObject(
                "SELECT retrospect_id FROM diaries WHERE id = ?", Long.class, diaryId);
        // 같은 회고에 딸린 행동 카드 — 삭제 뒤에도 자기 보관함에 남아야 한다
        seedCardRow(user, retrospectId, Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(delete("/api/v1/diaries/{id}", diaryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertRowCount("diaries", "id", diaryId, 0);
        // 스펙 스냅샷과의 차이: 행동 카드·회고 세션은 함께 지우지 않는다(PRODUCT.md 「상류와 차이」)
        assertRowCount("action_cards", "retrospect_id", retrospectId, 1);
        assertRowCount("retrospects", "id", retrospectId, 1);
    }

    @Test
    @DisplayName("삭제 — 남의 일기는 404, 지워지지 않는다")
    void deletingAnotherUsersDiaryIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(owner, "남의 일기", null, Emotion.CALM, null,
                Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(delete("/api/v1/diaries/{id}", diaryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));

        assertRowCount("diaries", "id", diaryId, 1);
    }

    @Test
    @DisplayName("삭제 — 없는 일기는 404")
    void deletingMissingDiaryIs404() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(delete("/api/v1/diaries/{id}", 999_999)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제는 인증이 필요하다 — 없으면 401")
    void deleteRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/diaries/{id}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("본문 수정 — 회고 id 로 찾아 original 을 바꾼다(reframed·감정은 그대로)")
    void updateByRetrospectChangesOriginal() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "원래 본문", "바바의 문장", Emotion.DEPRESSED, Emotion.ANXIOUS,
                Instant.parse("2026-08-14T02:00:00Z"));
        long retrospectId = jdbcTemplate.queryForObject(
                "SELECT retrospect_id FROM diaries WHERE id = ?", Long.class, diaryId);

        mockMvc.perform(put("/api/v1/diaries/by-retrospect/{retrospectId}", retrospectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"내가 고친 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) diaryId))
                .andExpect(jsonPath("$.original").value("내가 고친 본문"))
                // 바바의 문장·감정은 건드리지 않는다
                .andExpect(jsonPath("$.reframed").value("바바의 문장"))
                .andExpect(jsonPath("$.currentEmotion").value("depressed"));

        String stored = jdbcTemplate.queryForObject(
                "SELECT original FROM diaries WHERE id = ?", String.class, diaryId);
        org.junit.jupiter.api.Assertions.assertEquals("내가 고친 본문", stored);
    }

    @Test
    @DisplayName("본문 수정 — 남의 회고면 404, 바뀌지 않는다")
    void updatingAnotherUsersDiaryIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(owner, "원래 본문", null, Emotion.CALM, null,
                Instant.parse("2026-08-14T02:00:00Z"));
        long retrospectId = jdbcTemplate.queryForObject(
                "SELECT retrospect_id FROM diaries WHERE id = ?", Long.class, diaryId);

        mockMvc.perform(put("/api/v1/diaries/by-retrospect/{retrospectId}", retrospectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"남이 고침\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));

        String stored = jdbcTemplate.queryForObject(
                "SELECT original FROM diaries WHERE id = ?", String.class, diaryId);
        org.junit.jupiter.api.Assertions.assertEquals("원래 본문", stored);
    }

    @Test
    @DisplayName("본문 수정 — 빈 본문은 400")
    void updatingWithBlankOriginalIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "원래 본문", null, Emotion.CALM, null,
                Instant.parse("2026-08-14T02:00:00Z"));
        long retrospectId = jdbcTemplate.queryForObject(
                "SELECT retrospect_id FROM diaries WHERE id = ?", Long.class, diaryId);

        mockMvc.perform(put("/api/v1/diaries/by-retrospect/{retrospectId}", retrospectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("본문 수정은 인증이 필요하다 — 없으면 401")
    void updateRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/diaries/by-retrospect/{retrospectId}", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private void assertRowCount(String table, String column, long value, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }

    /** 회고에 딸린 행동 카드 한 장을 직접 넣는다 — 일기 삭제 뒤에도 남는지 보려는 것. */
    private void seedCardRow(User user, long retrospectId, Instant createdAt) {
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        LocalDate createdDate = createdAt.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        jdbcTemplate.update("""
                INSERT INTO action_cards (user_id, retrospect_id, situation, target_action,
                                          created_date, from_rest_preference, done,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospectId, "상황", "행동", createdDate, false, false, at, at);
    }

    /** 회고(FK 대상)를 실제 저장하고, 그에 딸린 일기를 지정한 {@code createdAt} 으로 직접 넣는다. */
    private long seedDiary(User user, String original, String reframed, Emotion current,
            Emotion schedule, Instant createdAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, current, "{}"));
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO diaries (user_id, retrospect_id, original, reframed, current_emotion,
                                     schedule_emotion, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospect.getId(), original, reframed, current.name(),
                schedule == null ? null : schedule.name(), at, at);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM diaries WHERE retrospect_id = ?", Long.class, retrospect.getId());
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
