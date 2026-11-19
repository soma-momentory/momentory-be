package com.momentory.diary.presentation;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired com.momentory.diary.infrastructure.DiaryEventCodec diaryEventCodec;
    @Autowired com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec stateCodec;
    @Autowired com.momentory.diary.application.DiaryEmotionService diaryEmotions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.momentory.retrospect.domain.assistant.EmotionExtractor emotionExtractor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(emotionExtractor.extract(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.momentory.retrospect.domain.assistant.EmotionExtraction.empty());
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

        // 8월(KST · 04:00 하루 경계) — 셋. 하나는 8/1 04:00 KST 경계(포함).
        seedDiary(user, "8월 늦은 일기", Emotion.SAD, Instant.parse("2026-08-20T10:00:00Z"));
        seedDiary(user, "8월 중간 일기", Emotion.ANGRY, Instant.parse("2026-08-14T02:23:47Z"));
        seedDiary(user, "8월 경계 포함", Emotion.CALM, Instant.parse("2026-07-31T19:00:00Z")); // = 2026-08-01T04:00 KST (하루 경계)
        // 7월(KST) 경계 제외 — 2026-08-01 03:59:59 KST 는 4시 전이라 아직 7/31 이다.
        seedDiary(user, "7월 경계 제외", Emotion.EXHAUSTED, Instant.parse("2026-07-31T18:59:59Z"));
        // 9월(KST) 제외.
        seedDiary(user, "9월 일기", Emotion.PROUD, Instant.parse("2026-09-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(3))
                // 최신순: 8/20 → 8/14 → 8/1(경계)
                .andExpect(jsonPath("$.diaries[0].original").value("8월 늦은 일기"))
                .andExpect(jsonPath("$.diaries[0].primaryEmotion").value("sad"))
                .andExpect(jsonPath("$.diaries[0].createdAt").exists())
                .andExpect(jsonPath("$.diaries[0].retrospectId").exists())
                .andExpect(jsonPath("$.diaries[1].original").value("8월 중간 일기"))
                .andExpect(jsonPath("$.diaries[2].original").value("8월 경계 포함"));
    }

    @Test
    @DisplayName("월별 목록 — 남의 일기는 섞이지 않는다")
    void monthlyListIsScopedToOwner() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        seedDiary(owner, "내 8월 일기", Emotion.SAD, Instant.parse("2026-08-10T01:00:00Z"));
        seedDiary(other, "남의 8월 일기", Emotion.JOY, Instant.parse("2026-08-11T01:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .param("year", "2026")
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(1))
                .andExpect(jsonPath("$.diaries[0].original").value("내 8월 일기"));
    }

    @Test
    @DisplayName("전체 목록 — 월 필터 없이 이 사용자의 모든 일기를 최신순으로 돌려준다")
    void listAllReturnsEveryDiaryNewestFirst() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        // 여러 달에 걸친 일기 — 월 경계와 무관하게 전부 와야 한다.
        seedDiary(user, "9월 일기", Emotion.PROUD, Instant.parse("2026-09-01T00:00:00Z"));
        seedDiary(user, "8월 일기", Emotion.SAD, Instant.parse("2026-08-14T02:00:00Z"));
        seedDiary(user, "7월 일기", Emotion.CALM, Instant.parse("2026-07-10T01:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries/all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(3))
                // 최신순: 9월 → 8월 → 7월
                .andExpect(jsonPath("$.diaries[0].original").value("9월 일기"))
                .andExpect(jsonPath("$.diaries[1].original").value("8월 일기"))
                .andExpect(jsonPath("$.diaries[2].original").value("7월 일기"));
    }

    @Test
    @DisplayName("전체 목록 — 남의 일기는 섞이지 않는다")
    void listAllIsScopedToOwner() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        seedDiary(owner, "내 일기", Emotion.SAD, Instant.parse("2026-08-10T01:00:00Z"));
        seedDiary(other, "남의 일기", Emotion.JOY, Instant.parse("2026-08-11T01:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries/all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(1))
                .andExpect(jsonPath("$.diaries[0].original").value("내 일기"));
    }

    @Test
    @DisplayName("전체 목록 — 일기가 없으면 빈 배열")
    void listAllReturnsEmptyWhenNone() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/diaries/all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diaries.length()").value(0));
    }

    @Test
    @DisplayName("전체 목록 — 인증 없으면 401")
    void listAllRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/diaries/all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("단건 조회 — 전체 본문을 돌려준다")
    void getOneReturnsFullBody() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "본문 전체", Emotion.SAD, Instant.parse("2026-08-14T02:00:00Z"));

        mockMvc.perform(get("/api/v1/diaries/{id}", diaryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) diaryId))
                .andExpect(jsonPath("$.original").value("본문 전체"))
                .andExpect(jsonPath("$.primaryEmotion").value("sad"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("단건 조회 — 남의 일기는 404")
    void getOneOfAnotherUserIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(owner, "남의 일기", Emotion.CALM, Instant.parse("2026-08-14T02:00:00Z"));

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
        long diaryId = seedDiary(user, "지울 일기", Emotion.CALM, Instant.parse("2026-08-14T02:00:00Z"));
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
        long diaryId = seedDiary(owner, "남의 일기", Emotion.CALM, Instant.parse("2026-08-14T02:00:00Z"));

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
    @DisplayName("본문 수정 — 회고 id 로 찾아 original 을 바꾼다(대표 감정은 그대로)")
    void updateByRetrospectChangesOriginal() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(user, "원래 본문", Emotion.SAD, Instant.parse("2026-08-14T02:00:00Z"));
        long retrospectId = jdbcTemplate.queryForObject(
                "SELECT retrospect_id FROM diaries WHERE id = ?", Long.class, diaryId);

        mockMvc.perform(put("/api/v1/diaries/by-retrospect/{retrospectId}", retrospectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"내가 고친 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) diaryId))
                .andExpect(jsonPath("$.original").value("내가 고친 본문"))
                // 대표 감정은 건드리지 않는다
                .andExpect(jsonPath("$.primaryEmotion").value("sad"));

        String stored = jdbcTemplate.queryForObject(
                "SELECT original FROM diaries WHERE id = ?", String.class, diaryId);
        org.junit.jupiter.api.Assertions.assertEquals("내가 고친 본문", stored);
    }

    @Test
    @DisplayName("본문 수정 — 남의 회고면 404, 바뀌지 않는다")
    void updatingAnotherUsersDiaryIs404() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long diaryId = seedDiary(owner, "원래 본문", Emotion.CALM, Instant.parse("2026-08-14T02:00:00Z"));
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
        long diaryId = seedDiary(user, "원래 본문", Emotion.CALM, Instant.parse("2026-08-14T02:00:00Z"));
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

    @Test
    void recordedWeatherIsStillReturnedByReadsAndTheRecordingApiIsGone() throws Exception {
        // 일기 저장 때 위치를 받아 날씨를 기록하던 API 는 없앴다. 이미 기록된 날씨는 조회에 그대로 나온다.
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiary(user, "오늘 일기", Emotion.CALM, Instant.now());
        jdbcTemplate.update("update diaries set weather = ? where id = ?", "맑음 · 22℃", id);
        mockMvc.perform(get("/api/v1/diaries/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.weather").value("맑음 · 22℃"));
        mockMvc.perform(get("/api/v1/diaries/all").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.diaries[0].weather").value("맑음 · 22℃"));
        mockMvc.perform(put("/api/v1/diaries/{id}/weather", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.57,\"longitude\":126.98}"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getStatus() >= 400, "기록 API 는 더 이상 받지 않는다"));
    }

    @Test
    void writesWithoutAiSessionAndCanReadEditAndDelete() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long sessions = retrospectRepository.count();
        mockMvc.perform(post("/api/v1/diaries")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"내가 쓴 글\\n둘째 줄\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retrospectId").doesNotExist())
                .andExpect(jsonPath("$.emotions").isEmpty())
                .andExpect(jsonPath("$.original").value("내가 쓴 글\n둘째 줄"));
        var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
        org.junit.jupiter.api.Assertions.assertNull(diary.getRetrospectId());
        org.junit.jupiter.api.Assertions.assertEquals(sessions, retrospectRepository.count());
        mockMvc.perform(get("/api/v1/diaries/all").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diaries[0].original").value("내가 쓴 글\n둘째 줄"));
        mockMvc.perform(put("/api/v1/diaries/{id}", diary.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"고친 글\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.original").value("고친 글"));
        org.junit.jupiter.api.Assertions.assertEquals("고친 글", diaryRepository.findById(diary.getId()).orElseThrow().getOriginal());
        mockMvc.perform(delete("/api/v1/diaries/{id}", diary.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertFalse(diaryRepository.existsById(diary.getId()));
    }

    @Test
    void directWritingRequiresAuthenticationAndValidBody() throws Exception {
        mockMvc.perform(post("/api/v1/diaries").contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"글\"}"))
                .andExpect(status().isUnauthorized());
        User user = userRepository.saveAndFlush(User.create());
        for (String invalid : new String[] { "{\"original\":\" \"}", "{}",
                "{\"original\":\"" + "가".repeat(501) + "\"}" }) {
            mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        org.junit.jupiter.api.Assertions.assertTrue(diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).isEmpty());
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void directWritingCannotDuplicateTodayOrEditAnotherUsersDiary() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long id = seedDiary(user, "AI 일기", Emotion.CALM, Instant.now());
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"직접 쓴 글\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RETROSPECTED_TODAY"));
        mockMvc.perform(put("/api/v1/diaries/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"남의 글 변경\"}"))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals("AI 일기", diaryRepository.findById(id).orElseThrow().getOriginal());
        org.junit.jupiter.api.Assertions.assertEquals(1, diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size());
    }

    @Test
    void selectedPhotoEmotionIsPersistedAndPreservedWhenEditingTheBody() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"행복했던 하루\",\"emotion\":\"joy\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primaryEmotion").value("joy"))
                .andExpect(jsonPath("$.emotions[0]").value("joy"));
        var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
        org.junit.jupiter.api.Assertions.assertEquals(Emotion.JOY, diary.getPrimaryEmotion());
        org.junit.jupiter.api.Assertions.assertEquals("joy", diary.getEmotions());
        mockMvc.perform(get("/api/v1/diaries/{id}", diary.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emotions[0]").value("joy"));
        mockMvc.perform(put("/api/v1/diaries/{id}", diary.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"고친 본문\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.primaryEmotion").value("joy"));
        org.junit.jupiter.api.Assertions.assertEquals("joy", diaryRepository.findById(diary.getId()).orElseThrow().getEmotions());
    }

    @Test
    void newTwelveEmotionKeysAreAcceptedForThePhotoEmotion() throws Exception {
        for (String key : new String[] { "affection", "disappointed", "ashamed", "sorry", "exhausted" }) {
            User user = userRepository.saveAndFlush(User.create());
            mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"original\":\"본문\",\"emotion\":\"" + key + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.primaryEmotion").value(key))
                    .andExpect(jsonPath("$.emotions[0]").value(key));
            org.junit.jupiter.api.Assertions.assertEquals(key,
                    diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst().getEmotions());
        }
    }

    @Test
    void legacyTenEmotionKeysFromOlderAppsAreStoredAsTheNewKeys() throws Exception {
        String[][] pairs = { { "happy", "joy" }, { "depressed", "sad" }, { "frustrated", "angry" },
                { "stuck", "confused" }, { "lethargic", "exhausted" }, { "tired", "exhausted" } };
        for (String[] pair : pairs) {
            User user = userRepository.saveAndFlush(User.create());
            mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"original\":\"본문\",\"emotion\":\"" + pair[0] + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.primaryEmotion").value(pair[1]))
                    .andExpect(jsonPath("$.emotions[0]").value(pair[1]));
            var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
            org.junit.jupiter.api.Assertions.assertEquals(Emotion.fromKey(pair[1]).orElseThrow(),
                    diary.getPrimaryEmotion());
            org.junit.jupiter.api.Assertions.assertEquals(pair[1], diary.getEmotions());
        }
    }

    @Test
    void invalidPhotoEmotionDoesNotCreateADiary() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        for (String emotion : new String[] { "unknown", "", "HAPPY", "JOY", "ANGRY_FRUSTRATED" }) {
            mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"original\":\"본문\",\"emotion\":\"" + emotion + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("감정을 확인해주세요."));
        }
        org.junit.jupiter.api.Assertions.assertTrue(diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).isEmpty());
    }

    @Test
    void writtenEventsAreSavedWithTheDiaryAndReplacedWhenItsBodyChanges() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        var extraction = new com.momentory.retrospect.domain.assistant.EmotionExtraction(
                java.util.List.of(
                        new com.momentory.retrospect.domain.ExtractedEvent(1, "친구와 산책", "친구와 산책하며 차분해졌다", java.util.List.of(1)),
                        new com.momentory.retrospect.domain.ExtractedEvent(2, "회사 회의", "긴 회의로 피곤했다", java.util.List.of(1)),
                        new com.momentory.retrospect.domain.ExtractedEvent(3, "근거 없는 사건", "본문 밖 사건", java.util.List.of(2))),
                java.util.List.of(new com.momentory.retrospect.domain.ExtractedEmotion(1, "차분", Emotion.CALM, 2, null, "차분해졌다", java.util.List.of(1))), null);
        org.mockito.Mockito.when(emotionExtractor.extract(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var source = (com.momentory.retrospect.domain.RetrospectState) call.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("친구와 산책하며 차분해졌다. 회의가 길어 피곤했다.", source.messages().getFirst().content());
            return extraction;
        });
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"original\":\"친구와 산책하며 차분해졌다. 회의가 길어 피곤했다.\",\"emotion\":\"joy\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.primaryEmotion").value("joy"));
        var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
        var events = diaryEventCodec.deserialize(diary.getEventsJson());
        org.assertj.core.api.Assertions.assertThat(events).extracting(com.momentory.diary.domain.DiaryEvent::label)
                .containsExactly("친구와 산책", "회사 회의");
        org.assertj.core.api.Assertions.assertThat(events.getFirst().emotions()).containsExactly(Emotion.CALM);
        org.junit.jupiter.api.Assertions.assertEquals(Emotion.JOY, diary.getPrimaryEmotion());
        org.mockito.Mockito.doReturn(
                new com.momentory.retrospect.domain.assistant.EmotionExtraction(java.util.List.of(
                        new com.momentory.retrospect.domain.ExtractedEvent(1, "책 읽기", "집에서 책을 읽었다", java.util.List.of(1))), java.util.List.of(), Emotion.CALM))
                .when(emotionExtractor).extract(org.mockito.ArgumentMatchers.any());
        mockMvc.perform(put("/api/v1/diaries/{id}", diary.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"집에서 책을 읽었다.\"}"))
                .andExpect(status().isOk());
        var updated = diaryRepository.findById(diary.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("집에서 책을 읽었다.", updated.getOriginal());
        var updatedEvents = diaryEventCodec.deserialize(updated.getEventsJson());
        org.assertj.core.api.Assertions.assertThat(updatedEvents).extracting(com.momentory.diary.domain.DiaryEvent::label).containsExactly("책 읽기");
        org.assertj.core.api.Assertions.assertThat(updatedEvents.getFirst().emotions()).isEmpty();
        org.junit.jupiter.api.Assertions.assertEquals(Emotion.JOY, updated.getPrimaryEmotion());
    }

    @Test
    void inferredEmotionDoesNotReplaceTheNeutralPhotoOrBecomeEventEvidence() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        org.mockito.Mockito.when(emotionExtractor.extract(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.momentory.retrospect.domain.assistant.EmotionExtraction(java.util.List.of(
                        new com.momentory.retrospect.domain.ExtractedEvent(1, "산책", "산책했다", java.util.List.of(1))), java.util.List.of(), Emotion.EXHAUSTED));
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"산책했다.\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.primaryEmotion").doesNotExist())
                .andExpect(jsonPath("$.emotions").isEmpty());
        var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
        org.junit.jupiter.api.Assertions.assertNull(diary.getPrimaryEmotion());
        org.assertj.core.api.Assertions.assertThat(diaryEventCodec.deserialize(diary.getEventsJson()).getFirst().emotions()).isEmpty();
    }

    @Test
    void eventEmotionsRequireEvidenceInTheWrittenBody() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        org.mockito.Mockito.when(emotionExtractor.extract(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.momentory.retrospect.domain.assistant.EmotionExtraction(java.util.List.of(
                        new com.momentory.retrospect.domain.ExtractedEvent(1, "산책", "산책했다", java.util.List.of(1))),
                        java.util.List.of(
                                new com.momentory.retrospect.domain.ExtractedEmotion(1, "화남", Emotion.ANGRY, 2, null, "산책했다", java.util.List.of(2)),
                                new com.momentory.retrospect.domain.ExtractedEmotion(1, "행복", Emotion.JOY, 2, null, "행복했다", java.util.List.of(1)),
                                new com.momentory.retrospect.domain.ExtractedEmotion(1, "평온", Emotion.CALM, 2, null, "", java.util.List.of(1))), null));
        mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"산책했다.\"}"))
                .andExpect(status().isCreated());
        var diary = diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).getFirst();
        org.assertj.core.api.Assertions.assertThat(diaryEventCodec.deserialize(diary.getEventsJson()).getFirst().emotions()).isEmpty();
    }

    @Test
    void concurrentDirectWritesLeaveOnlyOneDiary() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String token = bearer(user);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.Callable<Integer> write = () -> {
                ready.countDown();
                if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("requests not ready");
                return mockMvc.perform(post("/api/v1/diaries").header(HttpHeaders.AUTHORIZATION, token)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"내 글\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = workers.submit(write);
            var second = workers.submit(write);
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(
                    first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, diaryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size());
    }

    @Test
    void eventEmotionsPreserveOriginalAndCountOnlyOriginalRemoval() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        long retrospectId = diaryRepository.findById(id).orElseThrow().getRetrospectId();
        String source = retrospectRepository.findById(retrospectId).orElseThrow().getStateJson();
        mockMvc.perform(get("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].label").value("친구와 만남"))
                .andExpect(jsonPath("$.events[0].emotions[0]").value("joy"))
                .andExpect(jsonPath("$.events[1].emotions[0]").value("joy"))
                .andExpect(jsonPath("$.unlinkedEmotions[0]").value("sad"))
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        String added = "{\"events\":[{\"eventId\":1,\"emotions\":[\"joy\",\"calm\"]},{\"eventId\":2,\"emotions\":[\"joy\"]}],\"unlinkedEmotions\":[\"sad\",\"ashamed\"]}";
        putEmotions(user, id, added).andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        String baseline = diaryRepository.findById(id).orElseThrow().getOriginalEmotionSelectionJson();
        String restored = "{\"events\":[{\"eventId\":1,\"emotions\":[\"joy\"]},{\"eventId\":2,\"emotions\":[\"joy\"]}],\"unlinkedEmotions\":[\"sad\"]}";
        putEmotions(user, id, restored).andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        String corrected = "{\"events\":[{\"eventId\":1,\"emotions\":[\"calm\"]},{\"eventId\":2,\"emotions\":[\"joy\"]}],\"unlinkedEmotions\":[\"sad\"]}";
        putEmotions(user, id, corrected).andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        String removed = corrected.replace("\"joy\"", "\"calm\"");
        putEmotions(user, id, removed).andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionClassificationChanged").value(true))
                .andExpect(jsonPath("$.emotionClassificationChangedAt").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(diaryRepository.findById(id).orElseThrow().isEmotionClassificationChanged()).isTrue();
        putEmotions(user, id, removed).andExpect(status().isOk());
        putEmotions(user, id, restored).andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        var saved = diaryRepository.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.isEmotionClassificationChanged()).isFalse();
        org.assertj.core.api.Assertions.assertThat(saved.getOriginalEmotionSelectionJson()).isEqualTo(baseline);
        org.assertj.core.api.Assertions.assertThat(retrospectRepository.findById(retrospectId).orElseThrow().getStateJson()).isEqualTo(source);
        org.assertj.core.api.Assertions.assertThat(saved.getOriginal()).isEqualTo("친구를 만나고 산책했다.");
        org.assertj.core.api.Assertions.assertThat(saved.getEmotions()).isEqualTo("joy,sad");
    }

    @Test
    void confirmedChatEmotionsStayLinkedToTheSelectedEvent() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        long retrospectId = diaryRepository.findById(id).orElseThrow().getRetrospectId();
        var state = stateCodec.deserialize(retrospectRepository.findById(retrospectId).orElseThrow().getStateJson());
        state.selectEvent(2);
        state.confirmEmotions(java.util.List.of(Emotion.AFFECTION));
        jdbcTemplate.update("UPDATE retrospects SET state_json = ? WHERE id = ?", stateCodec.serialize(state), retrospectId);
        jdbcTemplate.update("UPDATE diaries SET primary_emotion = 'AFFECTION', emotions = 'affection,joy,sad' WHERE id = ?", id);
        mockMvc.perform(get("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].emotions[0]").value("joy"))
                .andExpect(jsonPath("$.events[1].emotions[0]").value("affection"))
                .andExpect(jsonPath("$.events[1].emotions[1]").value("joy"))
                .andExpect(jsonPath("$.unlinkedEmotions[0]").value("sad"));
    }

    @Test
    void sameEmotionCanBeBothEventLinkedAndUnlinkedInOriginalExtraction() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        long retrospectId = diaryRepository.findById(id).orElseThrow().getRetrospectId();
        var state = stateCodec.deserialize(retrospectRepository.findById(retrospectId).orElseThrow().getStateJson());
        var emotions = new java.util.ArrayList<>(state.emotions());
        emotions.add(new com.momentory.retrospect.domain.ExtractedEmotion(null, "기뻤다", Emotion.JOY, null, null, "기뻤다", java.util.List.of(3)));
        state.emotions(emotions);
        jdbcTemplate.update("UPDATE retrospects SET state_json = ? WHERE id = ?", stateCodec.serialize(state), retrospectId);
        mockMvc.perform(get("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].emotions[0]").value("joy"))
                .andExpect(jsonPath("$.unlinkedEmotions[0]").value("sad"))
                .andExpect(jsonPath("$.unlinkedEmotions[1]").value("joy"));
    }

    @Test
    void standaloneEmotionsAndClearingAllPersistWithoutInventingEvents() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiary(user, "짧은 기록", Emotion.SAD, Instant.now());
        putEmotions(user, id, "{\"events\":[],\"unlinkedEmotions\":[\"sad\",\"joy\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        putEmotions(user, id, "{\"events\":[],\"unlinkedEmotions\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isEmpty())
                .andExpect(jsonPath("$.emotions").isEmpty()).andExpect(jsonPath("$.emotionClassificationChanged").value(true));
        var saved = diaryRepository.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getPrimaryEmotion()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getEmotions()).isNull();
        mockMvc.perform(get("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emotions").isEmpty())
                .andExpect(jsonPath("$.unlinkedEmotions").isEmpty());
    }

    @Test
    void writtenDiarySelectionIsEditableButNeverCountsAsChatClassificationCorrection() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        var diary = com.momentory.diary.domain.Diary.write(user.getId(), "혼자 쓴 일기", Emotion.JOY);
        diary.replaceEvents(diaryEventCodec.serialize(java.util.List.of(
                new com.momentory.diary.domain.DiaryEvent("산책", "공원을 걸었다", java.util.List.of(Emotion.JOY)))));
        long id = diaryRepository.saveAndFlush(diary).getId();
        putEmotions(user, id, "{\"events\":[{\"eventId\":1,\"emotions\":[\"sad\"]}],\"unlinkedEmotions\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.emotions[0]").value("sad"))
                .andExpect(jsonPath("$.emotionClassificationChanged").value(false));
        org.assertj.core.api.Assertions.assertThat(diaryRepository.findById(id).orElseThrow().isEmotionClassificationChanged()).isFalse();
    }

    @Test
    void writtenBodyReplacementRefreshesEventsAndKeepsSelectedEmotions() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        var diary = com.momentory.diary.domain.Diary.write(user.getId(), "산책했다", Emotion.JOY);
        diary.replaceEvents(diaryEventCodec.serialize(java.util.List.of(
                new com.momentory.diary.domain.DiaryEvent("산책", "공원을 걸었다", java.util.List.of(Emotion.JOY)))));
        long id = diaryRepository.saveAndFlush(diary).getId();
        putEmotions(user, id, "{\"events\":[{\"eventId\":1,\"emotions\":[\"sad\"]}],\"unlinkedEmotions\":[]}")
                .andExpect(status().isOk());
        org.mockito.Mockito.when(emotionExtractor.extract(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.momentory.retrospect.domain.assistant.EmotionExtraction(
                        java.util.List.of(new com.momentory.retrospect.domain.ExtractedEvent(1, "직장 회의", "회의에 참석했다", java.util.List.of(1))),
                        java.util.List.of(), null));
        mockMvc.perform(put("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"original\":\"회의에 참석했다\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].label").value("직장 회의"))
                .andExpect(jsonPath("$.events[0].emotions").isEmpty())
                .andExpect(jsonPath("$.unlinkedEmotions[0]").value("sad"));
        mockMvc.perform(get("/api/v1/diaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].label").value("직장 회의"));
        putEmotions(user, id, "{\"events\":[{\"eventId\":1,\"emotions\":[\"sad\"]}],\"unlinkedEmotions\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].label").value("직장 회의"))
                .andExpect(jsonPath("$.events[0].emotions[0]").value("sad"));
    }

    @Test
    void invalidEmotionEditsAndOtherUsersCannotMutateDiary() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        String valid = "{\"events\":[{\"eventId\":1,\"emotions\":[]},{\"eventId\":2,\"emotions\":[]}],\"unlinkedEmotions\":[]}";
        putEmotions(other, id, valid).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/diaries/" + id + "/emotions").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        for (String invalid : java.util.List.of(
                "{\"events\":[],\"unlinkedEmotions\":[]}",
                "{\"events\":[{\"eventId\":1,\"emotions\":[]},{\"eventId\":1,\"emotions\":[]}],\"unlinkedEmotions\":[]}",
                valid.replace("eventId\":2", "eventId\":3"),
                valid.replace("\"unlinkedEmotions\":[]", "\"unlinkedEmotions\":[\"unknown\"]"),
                "{\"events\":null,\"unlinkedEmotions\":[]}",
                "{\"events\":[null],\"unlinkedEmotions\":[]}",
                "{\"events\":[],\"unlinkedEmotions\":[null]}",
                "{}")) {
            putEmotions(user, id, invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        var saved = diaryRepository.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getEmotionSelectionJson()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getOriginalEmotionSelectionJson()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.isEmotionClassificationChanged()).isFalse();
    }

    @Test
    void emotionSnapshotTagsAndCorrectionFlagRollBackTogether() {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            diaryEmotions.update(user.getId(), id, java.util.List.of(
                    new com.momentory.diary.application.DiaryEmotionService.EventSelection(1, java.util.List.of()),
                    new com.momentory.diary.application.DiaryEmotionService.EventSelection(2, java.util.List.of())), java.util.List.of());
            diaryRepository.flush();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        var saved = diaryRepository.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getPrimaryEmotion()).isEqualTo(Emotion.JOY);
        org.assertj.core.api.Assertions.assertThat(saved.getEmotionSelectionJson()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getOriginalEmotionSelectionJson()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.isEmotionClassificationChanged()).isFalse();
    }

    @Test
    void concurrentCorrectionAndRestorationKeepOneOriginalAndFlagMatchesSavedSelection() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long id = seedDiaryWithEvents(user);
        String corrected = "{\"events\":[{\"eventId\":1,\"emotions\":[]},{\"eventId\":2,\"emotions\":[]}],\"unlinkedEmotions\":[\"sad\"]}";
        String restored = "{\"events\":[{\"eventId\":1,\"emotions\":[\"joy\"]},{\"eventId\":2,\"emotions\":[\"joy\"]}],\"unlinkedEmotions\":[\"sad\"]}";
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            java.util.function.Function<String, java.util.concurrent.Callable<Integer>> request = json -> () -> {
                ready.countDown();
                if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("not ready");
                return putEmotions(user, id, json).andReturn().getResponse().getStatus();
            };
            var first = workers.submit(request.apply(corrected));
            var second = workers.submit(request.apply(restored));
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsOnly(200);
        }
        var saved = diaryRepository.findById(id).orElseThrow();
        var baseline = diaryEventCodec.deserializeSelection(saved.getOriginalEmotionSelectionJson());
        var selection = diaryEventCodec.deserializeSelection(saved.getEmotionSelectionJson());
        org.assertj.core.api.Assertions.assertThat(saved.isEmotionClassificationChanged()).isEqualTo(selection.removesFrom(baseline));
        org.assertj.core.api.Assertions.assertThat(baseline.events().getFirst().emotions()).containsExactly(Emotion.JOY);
        org.assertj.core.api.Assertions.assertThat(baseline.unlinkedEmotions()).containsExactly(Emotion.SAD);
    }

    private org.springframework.test.web.servlet.ResultActions putEmotions(User user, long id, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/diaries/" + id + "/emotions")
                .header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long seedDiaryWithEvents(User user) {
        var state = new com.momentory.retrospect.domain.RetrospectState("emotion-test");
        state.events(java.util.List.of(
                new com.momentory.retrospect.domain.ExtractedEvent(1, "친구와 만남", "친구와 저녁을 먹었다", java.util.List.of(1)),
                new com.momentory.retrospect.domain.ExtractedEvent(2, "산책", "공원을 걸었다", java.util.List.of(2))));
        state.emotions(java.util.List.of(
                new com.momentory.retrospect.domain.ExtractedEmotion(1, "좋았다", Emotion.JOY, null, null, "좋았다", java.util.List.of(1)),
                new com.momentory.retrospect.domain.ExtractedEmotion(2, "즐거웠다", Emotion.JOY, null, null, "즐거웠다", java.util.List.of(2)),
                new com.momentory.retrospect.domain.ExtractedEmotion(null, "슬펐다", Emotion.SAD, null, null, "슬펐다", java.util.List.of(3))));
        var retro = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(), RetrospectStatus.COMPLETED, null, stateCodec.serialize(state)));
        return diaryRepository.saveAndFlush(com.momentory.diary.domain.Diary.create(user.getId(), retro.getId(),
                "친구를 만나고 산책했다.", Emotion.JOY, "joy,sad")).getId();
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
    private long seedDiary(User user, String original, Emotion current, Instant createdAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, "{}"));
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO diaries (user_id, retrospect_id, original, primary_emotion,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospect.getId(), original, current.name(), at, at);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM diaries WHERE retrospect_id = ?", Long.class, retrospect.getId());
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
