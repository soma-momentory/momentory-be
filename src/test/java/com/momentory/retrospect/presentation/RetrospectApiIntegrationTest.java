package com.momentory.retrospect.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.TurnScripter;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;
import com.momentory.retrospect.domain.assistant.UnderstandingChecker;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 회고 REST 계약 통합 검증 — 실제 HTTP + 인증 + DB 로 시작→턴→완료까지.
 * AI 포트는 모킹한다(실 Gemini 검증은 GeminiApiClientRealIntegrationTest, opt-in).
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class RetrospectApiIntegrationTest {

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
    @Autowired ActionCardRepository actionCardRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean UnderstandingChecker understandingChecker;
    @MockitoBean TurnScripter turnScripter;
    @MockitoBean DiaryWriter diaryWriter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        when(understandingChecker.check(any(), anyString())).thenReturn(Optional.of(
                new UnderstandingCheck("답변을 제대로 하지 못해 불안했던 것 같네요.",
                        "모의 면접에서 답변을 제대로 말하지 못함", "none", List.of(), false, false,
                        false)));
        when(turnScripter.script(any(), any())).thenAnswer(inv -> Optional.of(new TurnScript(
                "다듬어진 질문", List.of(new OptionItem("보기1", null), new OptionItem("보기2", null),
                        new OptionItem("보기3", null)),
                "none", List.of(), false, false, false)));
        when(diaryWriter.write(any())).thenReturn(Optional.of(
                new DiaryOutput("그냥 일기.", "리프레이밍 일기.")));
    }

    @AfterEach
    void cleanUp() {
        // 일기·행동 카드가 회고를 FK 로 참조하므로 그것부터 지운다
        diaryRepository.deleteAllInBatch();
        actionCardRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("시작 — 세션이 만들어지고 201 + 1턴 질문 + DB에 진행중 레코드가 남는다")
    void startCreatesSessionAndPersists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        MvcResult result = mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "currentEmotion":"depressed","nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.reply.phase").value("intro"))
                .andExpect(jsonPath("$.reply.text",
                        Matchers.containsString("오늘 면접 스터디에서는 불안했고 지금은 우울하다고 했네요")))
                .andReturn();

        long sessionId = sessionId(result);
        Retrospect saved = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getStatus().name()).isEqualTo("IN_PROGRESS");
        assertThat(saved.getStateJson()).contains("면접 스터디");
    }

    @Test
    @DisplayName("시작 검증 — 현재 감정 없음/잘못된 일정 감정 키는 400 {code,message}")
    void startValidationReturnsErrorContract() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("currentEmotion은 필수입니다."));

        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"산책","emotion":"nope"}],
                                 "currentEmotion":"depressed"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", Matchers.containsString("schedules[0].emotion")));
    }

    @Test
    @DisplayName("하루 한 번 — 오늘(KST) 일기가 이미 있으면 시작은 409 {code,message}")
    void startBlockedWhenTodayDiaryAlreadyExists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        // 오늘 완주한 일기 한 벌 — Diary 의 @PrePersist 가 created_at 을 지금(=오늘 KST)으로 박는다.
        Retrospect done = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, Emotion.DEPRESSED, "{}"));
        diaryRepository.saveAndFlush(Diary.create(user.getId(), done.getId(), "오늘 일기", null,
                Emotion.DEPRESSED, null));

        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "currentEmotion":"depressed","nickname":"정민"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RETROSPECTED_TODAY"))
                .andExpect(jsonPath("$.message", Matchers.containsString("하루에 한 번")));

        // 가드는 시작을 막았을 뿐 — 새 회고 레코드는 남지 않는다(원래 완주 한 벌만).
        assertThat(retrospectRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("하루 한 번 — 어제 일기뿐이면 오늘 시작은 막지 않는다(201)")
    void startAllowedWhenOnlyYesterdayDiaryExists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Retrospect done = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, Emotion.DEPRESSED, "{}"));
        Diary yesterday = diaryRepository.saveAndFlush(Diary.create(user.getId(), done.getId(),
                "어제 일기", null, Emotion.DEPRESSED, null));
        // created_at 을 어제로 되돌린다(@PrePersist 가 now 로 박으므로 직접 UPDATE).
        java.time.OffsetDateTime yesterdayAt = java.time.OffsetDateTime.ofInstant(
                Instant.now().minus(java.time.Duration.ofDays(1)), java.time.ZoneOffset.UTC);
        jdbcTemplate.update("UPDATE diaries SET created_at = ? WHERE id = ?",
                yesterdayAt, yesterday.getId());

        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "currentEmotion":"depressed","nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNumber());
    }

    @Test
    @DisplayName("턴 흐름 — 1턴 답변 뒤 방향 4택이 계약대로(options[].id/label/description) 온다")
    void directionOptionsContract() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        mockMvc.perform(message(user, sessionId, "{\"content\":\"답변을 제대로 못 했어요.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("await_direction"))
                .andExpect(jsonPath("$.options", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.options[0].id").value("1"))
                .andExpect(jsonPath("$.options[0].description").value("감정 정리형"));
    }

    @Test
    @DisplayName("짧은 기록형 끝까지 — 슬라이더 계약과 일기 1종, DB가 완료로 전이된다")
    void shortRecordEndToEndPersistsCompletion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        mockMvc.perform(message(user, sessionId, "{\"content\":\"답변을 제대로 못 했어요.\"}"))
                .andExpect(status().isOk());

        // 방향 4택 → 짧은 기록형(4) → 측정 UI(0~10)
        mockMvc.perform(message(user, sessionId, "{\"optionId\":\"4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ui").value("measure"))
                .andExpect(jsonPath("$.measures[0].max").value(10));

        mockMvc.perform(message(user, sessionId, """
                        {"measures":{"schedule_emotion":8,"current_emotion":6}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(message(user, sessionId, "{\"content\":\"불안했고 우울해졌다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.diary.diary").value("그냥 일기."))
                // 짧은 기록형은 리프레이밍 일기가 내려가지 않는다.
                .andExpect(jsonPath("$.diary.reframedDiary").doesNotExist());

        Retrospect done = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(done.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(done.getCompletedAt()).isNotNull();

        // 일기는 별도 테이블에 남고, 진입 감정 두 종과 생성 시기도 함께 저장된다.
        Diary diary = diaryRepository.findByRetrospectId(sessionId).orElseThrow();
        assertThat(diary.getOriginal()).isEqualTo("그냥 일기.");
        assertThat(diary.getReframed()).isNull(); // 짧은 기록형은 리프레임 일기가 없다
        assertThat(diary.getCurrentEmotion()).isEqualTo(Emotion.DEPRESSED);
        assertThat(diary.getScheduleEmotion()).isEqualTo(Emotion.ANXIOUS);
        assertThat(diary.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("소유권 — 남의 세션에는 접근할 수 없다(404)")
    void cannotAccessAnotherUsersSession() throws Exception {
        User owner = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(owner);

        mockMvc.perform(message(other, sessionId, "{\"content\":\"안녕\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RETROSPECT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 세션은 404 {code,message}")
    void unknownSessionIs404() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(message(user, 999_999L, "{\"content\":\"안녕\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RETROSPECT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없으면 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/retrospect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentEmotion":"depressed"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }


    // ── 도우미 ───────────────────────────────────────────────────────────

    private long startSession(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "currentEmotion":"depressed","nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        return sessionId(result);
    }

    private MockHttpServletRequestBuilder message(User user, long sessionId, String body) {
        return post("/api/v1/retrospect/{id}/messages", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private long sessionId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.sessionId");
        return id.longValue();
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
