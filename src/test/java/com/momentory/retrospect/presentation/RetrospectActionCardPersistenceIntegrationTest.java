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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import com.momentory.actioncard.application.SituationEmbedder;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.TurnScripter;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;
import com.momentory.retrospect.domain.assistant.UnderstandingChecker;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 행동 카드 영속화 재현 — 카드가 생기는 모드(문제 해결형)를 실제 HTTP 로 완주시켜, 완료 턴에서
 * 카드가 DB 에 남는지 확인한다.
 *
 * <p>이 경로는 기존 통합 테스트가 비워 둔 곳이다: 완주 테스트는 카드가 없는 짧은 기록형뿐이고,
 * 카드 조회 테스트는 카드를 저장소로 직접 넣어 두고 읽기만 봤다. 여기서는 <b>엔진이 만든 카드를
 * 서비스가 저장하는 실제 경로</b>와, 그 저장이 임베딩 실패에 얽매이는지를 본다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class RetrospectActionCardPersistenceIntegrationTest {

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

    @MockitoBean UnderstandingChecker understandingChecker;
    @MockitoBean TurnScripter turnScripter;
    @MockitoBean DiaryWriter diaryWriter;
    @MockitoBean SituationEmbedder situationEmbedder;

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

    @Test
    @DisplayName("대조군 — 임베딩이 정상이면 완주 시 행동 카드가 DB 에 남는다")
    void persistsActionCardOnCompletion() throws Exception {
        // 추천 조회(액션 스텝) + 영속화(완료) 두 번의 embed 모두 정상 768 벡터.
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(768)));

        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);
        driveProblemSolvingToActionStep(user, sessionId);

        // 마지막 턴(측정) → 완료. 카드가 응답에 실린다.
        MvcResult completion = mockMvc.perform(message(user, sessionId, """
                        {"measures":{"schedule_emotion":8,"current_emotion":6}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.actionCard.action").value("보기1"))
                // 방금 저장된 행동 카드 id 가 완료 응답에 실려 온다 — 클라이언트가 다음 조회를
                // 기다리지 않고 이 id 로 「해봤어요」·느낀 점을 보낼 수 있다(일기 diaryId 와 같은 결).
                .andExpect(jsonPath("$.actionCard.actionCardId").isNumber())
                .andReturn();

        // 그리고 실제로 DB 에 남아 있어야 한다.
        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).as("완주한 문제 해결형 회고는 행동 카드를 남겨야 한다").isPresent();
        assertThat(saved.get().getTargetAction()).isEqualTo("보기1");
        // 응답에 실린 actionCardId 가 실제 저장된 카드 id 와 같다.
        int responseCardId = com.jayway.jsonpath.JsonPath.read(
                completion.getResponse().getContentAsString(), "$.actionCard.actionCardId");
        assertThat((long) responseCardId).isEqualTo(saved.get().getId());

        Retrospect done = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(done.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("회귀 가드 — 영속화 시점 임베딩이 실패해도 행동 카드는 남는다")
    void actionCardSurvivesEmbeddingFailure() throws Exception {
        // 1번째 embed(액션 스텝의 유사 추천)는 정상, 2번째 embed(완료 후 영속화)는 컬럼 차원(768)과
        // 안 맞는 벡터를 돌려준다 → updateEmbedding 의 CAST 가 터진다. 임베딩은 카드 커밋 뒤 별도
        // 트랜잭션에서 best-effort 로 돌므로, 이 실패는 삼켜지고 카드는 그대로 남아야 한다.
        when(situationEmbedder.embed(anyString()))
                .thenReturn(Optional.of(unitVector(768)))
                .thenReturn(Optional.of(unitVector(3072)));

        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);
        driveProblemSolvingToActionStep(user, sessionId);

        // 완료 턴 — 카드가 남는 것이 기대 동작이다(임베딩 실패는 무시돼야 한다).
        mockMvc.perform(message(user, sessionId, """
                        {"measures":{"schedule_emotion":8,"current_emotion":6}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));

        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).as("임베딩이 실패해도 행동 카드는 영속화돼야 한다").isPresent();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** 시작 → 1턴 답변 → 방향 '문제 해결형'(3) → 텍스트 3 → 목적 선택 → 행동 선택. 다음은 측정. */
    private void driveProblemSolvingToActionStep(User user, long sessionId) throws Exception {
        mockMvc.perform(message(user, sessionId, "{\"content\":\"답변을 제대로 못 했어요.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("await_direction"));
        // 방향 4택 중 3 = 문제 해결형(세부 방향 없이 바로 모드 진입, 카드 있음)
        mockMvc.perform(message(user, sessionId, "{\"optionId\":\"3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("script"));
        mockMvc.perform(message(user, sessionId, "{\"content\":\"발표에서 말이 막혔어요.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(message(user, sessionId, "{\"content\":\"핵심은 말하고 싶어요.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(message(user, sessionId, "{\"content\":\"미리 연습할 수 있어요.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options").isArray());
        // 목적 선택(action_purpose)
        mockMvc.perform(message(user, sessionId, "{\"optionId\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options").isArray());
        // 행동 선택(action step) → chosenAction 확정
        mockMvc.perform(message(user, sessionId, "{\"optionId\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ui").value("measure"));
    }

    private long startSession(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "currentEmotion":"depressed","nickname":"정민"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.sessionId")).longValue();
    }

    private MockHttpServletRequestBuilder message(User user, long sessionId, String body) {
        return post("/api/v1/retrospect/{id}/messages", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }

    /** 한 축만 1인 단위 벡터 float[] — 유사도 검증엔 방향만 쓰고, 차원으로 컬럼 적합성을 시험한다. */
    private static float[] unitVector(int dim) {
        float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }
}
