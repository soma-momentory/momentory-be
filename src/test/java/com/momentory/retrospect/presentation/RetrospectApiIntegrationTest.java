package com.momentory.retrospect.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;
import com.momentory.retrospect.domain.assistant.TopicExtractor;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 회고 REST 계약 통합 검증 (채팅흐름_v2) — 실제 HTTP + 인증 + DB 로 시작→일기작성→분기점→완료까지.
 * AI 포트 4종은 모킹해 흐름을 결정적으로 만든다(실 Gemini 검증은 opt-in 통합 테스트).
 *
 * <p>v2 계약: 시작은 감정을 고르지 않고(일정 이름만), 첫 phase 는 {@code diary_chat}. 일기 작성
 * 채팅을 최대 6턴 이어가면 분기점({@code await_branch})에서 2택("감정을 더 알아볼래요" /
 * "일기 확인하러 갈래요")이 온다. 턴 입력은 {@code content} 또는 {@code optionIds}.
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

    @MockitoBean DiaryChatAssistant diaryChatAssistant;
    @MockitoBean EmotionExtractor emotionExtractor;
    @MockitoBean ExplorationAssistant explorationAssistant;
    @MockitoBean DiaryWriter diaryWriter;
    @MockitoBean TopicExtractor topicExtractor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // 일기 작성 턴 — 사건·의미·감정 표현을 채운 슬롯과 다음 질문을 돌려준다.
        when(diaryChatAssistant.turn(any(), anyString())).thenReturn(Optional.of(new DiaryTurn(
                "면접 스터디에서 말이 막혔다", List.of(), "계속 곱씹게 된다", true,
                "조금 더 들려줄래요?", null, "none", List.of(), false, false)));
        // 대화 끝 감정 추출 — 일기 대표 감정·태그의 근거.
        when(emotionExtractor.extract(any())).thenReturn(List.of(
                new ExtractedEmotion("우울한 느낌", Emotion.DEPRESSED, null, null, "우울해요")));
        // 감정 탐색 후보(이 테스트에선 '감정 더 알아보기' 경로를 타지 않지만, 페이크를 안정적으로 둔다).
        when(explorationAssistant.suggestNeeds(any())).thenReturn(List.of());
        when(explorationAssistant.suggestActions(any())).thenReturn(List.of("따뜻한 물 한 잔 마시기"));
        // 짧은 기록형 일기 1종.
        when(diaryWriter.write(any())).thenReturn(Optional.of(new DiaryOutput("그냥 일기.", null)));
        // 토픽 추출은 이 테스트 범위 밖 — 빈 목록.
        when(topicExtractor.extract(any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        // 일기·행동 카드가 회고를 FK 로 참조하므로 그것부터 지운다.
        diaryRepository.deleteAllInBatch();
        actionCardRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("시작 — 세션이 만들어지고 201 + 일기작성 질문 + DB에 진행중 레코드가 남는다")
    void startCreatesSessionAndPersists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        MvcResult result = mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.reply.phase").value("diary_chat"))
                .andExpect(jsonPath("$.reply.text", Matchers.containsString("면접 스터디")))
                .andExpect(jsonPath("$.reply.options").doesNotExist())
                .andReturn();

        long sessionId = sessionId(result);
        Retrospect saved = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getStatus().name()).isEqualTo("IN_PROGRESS");
        assertThat(saved.getStateJson()).contains("면접 스터디");
    }

    @Test
    @DisplayName("시작 검증 — 잘못된 일정 감정 키는 400 {code,message}")
    void startValidationReturnsErrorContract() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        // v2 는 시작 감정을 고르지 않는다. 일정에 감정을 달 때만, 그 값이 9종 밖이면 거부한다.
        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"산책","emotion":"nope"}]}"""))
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
                RetrospectStatus.COMPLETED, null, "{}"));
        diaryRepository.saveAndFlush(Diary.create(user.getId(), done.getId(), "오늘 일기",
                Emotion.DEPRESSED, null));

        mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "nickname":"정민"}"""))
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
                RetrospectStatus.COMPLETED, null, "{}"));
        Diary yesterday = diaryRepository.saveAndFlush(Diary.create(user.getId(), done.getId(),
                "어제 일기", Emotion.DEPRESSED, null));
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
                                 "nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNumber());
    }

    @Test
    @DisplayName("분기점 — 일기 작성 6턴을 이어가면 분기 2택이 계약대로(options[].id/label) 온다")
    void branchOptionsContract() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        String phase = driveDiaryToBranch(user, sessionId);

        assertThat(phase).isEqualTo("await_branch");
    }

    @Test
    @DisplayName("완료(일기 확인) — 분기에서 '일기 확인하러 갈래요'면 바람카드 없이 일기로 완료·저장된다")
    void viewDiaryBranchCompletesAndPersists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);
        driveDiaryToBranch(user, sessionId);

        // 분기 2택의 두 번째(id "2") = "일기 확인하러 갈래요" → 감정 탐색 없이 완료.
        MvcResult completion = mockMvc.perform(message(user, sessionId, "{\"optionIds\":[\"2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("complete"))
                .andExpect(jsonPath("$.done").value(true))
                // 방금 저장된 일기의 id 가 완료 응답에 실려 온다 — 클라이언트가 다음 조회를 기다리지
                // 않고 이 id 로 삭제할 수 있다.
                .andExpect(jsonPath("$.diary.diaryId").isNumber())
                .andExpect(jsonPath("$.diary.diary").value("그냥 일기."))
                // 감정 탐색을 거치지 않은 완료라 바람 카드는 실리지 않는다.
                .andExpect(jsonPath("$.wishCard").doesNotExist())
                .andReturn();

        Retrospect done = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(done.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(done.getCompletedAt()).isNotNull();

        // 일기는 별도 테이블에 남고, 대표 감정·생성 시기도 함께 저장된다.
        Diary diary = diaryRepository.findByRetrospectId(sessionId).orElseThrow();
        int responseDiaryId = JsonPath.read(
                completion.getResponse().getContentAsString(), "$.diary.diaryId");
        assertThat((long) responseDiaryId).isEqualTo(diary.getId());
        assertThat(diary.getOriginal()).isEqualTo("그냥 일기.");
        assertThat(diary.getPrimaryEmotion()).isEqualTo(Emotion.DEPRESSED);
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
                        .content("{}"))
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
                                 "nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        return sessionId(result);
    }

    /**
     * 일기 작성 채팅을 분기점까지 몰아준다 — phase 가 {@code diary_chat} 인 동안 자유 텍스트 턴을
     * 계속 보낸다(엔진은 최대 6턴에서 분기점을 낸다). 분기점의 phase 를 돌려준다.
     */
    private String driveDiaryToBranch(User user, long sessionId) throws Exception {
        String phase = "diary_chat";
        for (int i = 0; i < 10 && "diary_chat".equals(phase); i++) {
            MvcResult turn = mockMvc.perform(
                            message(user, sessionId, "{\"content\":\"팀원이 말을 끊어서 속상했어요.\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            phase = JsonPath.read(turn.getResponse().getContentAsString(), "$.phase");
        }
        return phase;
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
