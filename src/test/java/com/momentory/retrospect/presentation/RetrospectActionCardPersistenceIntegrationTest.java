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
import com.momentory.actioncard.application.SituationEmbedder;
import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
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
 * 행동(바람) 카드 영속화 재현 (채팅흐름_v2) — 카드가 생기는 경로(감정 탐색을 거친 완주)를 실제
 * HTTP 로 몰아, 완료 턴에서 바람 카드가 DB 에 남는지 확인한다.
 *
 * <p>v2 에서 카드는 감정 탐색(분기점 "감정을 더 알아볼래요" → 감정·바람·작은 행동 3턴)을 지난
 * 완주에서만 생긴다. 여기서는 <b>엔진이 만든 바람 카드를 리스너가 저장하는 실제 경로</b>와, 그
 * 저장이 상황 임베딩 실패에 얽매이지 않는지(임베딩은 커밋 뒤 best-effort)를 본다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class RetrospectActionCardPersistenceIntegrationTest {

    private static final String SMALL_ACTION = "따뜻한 물 한 잔 마시기";

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

    @MockitoBean DiaryChatAssistant diaryChatAssistant;
    @MockitoBean EmotionExtractor emotionExtractor;
    @MockitoBean ExplorationAssistant explorationAssistant;
    @MockitoBean DiaryWriter diaryWriter;
    @MockitoBean TopicExtractor topicExtractor;
    @MockitoBean SituationEmbedder situationEmbedder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        when(diaryChatAssistant.turn(any(), anyString())).thenReturn(Optional.of(new DiaryTurn(
                "면접 스터디에서 팀원이 말을 끊었다", List.of(), "내 의견이 가볍게 다뤄진 게 걸린다", true,
                "조금 더 들려줄래요?", null, "none", List.of(), false, false)));
        // 대화 끝 감정 추출 — 감정 탐색 1턴의 후보가 된다.
        when(emotionExtractor.extract(any())).thenReturn(List.of(
                new ExtractedEmotion("무시당한 느낌", Emotion.ANGRY, null, null, "말을 끊겼어요")));
        // 바람 후보는 엔진 폴백(고정 앞자리)에 맡기고, 작은 행동만 정해 카드 내용을 확인한다.
        when(explorationAssistant.suggestNeeds(any())).thenReturn(List.of());
        when(explorationAssistant.suggestActions(any())).thenReturn(List.of(SMALL_ACTION));
        when(diaryWriter.write(any())).thenReturn(Optional.of(new DiaryOutput("그냥 일기.", null)));
        when(topicExtractor.extract(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("대조군 — 임베딩이 정상이면 완주 시 바람 카드가 DB 에 남는다")
    void persistsActionCardOnCompletion() throws Exception {
        // 완료 후 상황 임베딩은 정상 768 벡터로 채워진다(카드 커밋 뒤 별도 트랜잭션).
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(768)));

        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        MvcResult completion = driveExplorationToCompletion(user, sessionId);

        assertThat((boolean) JsonPath.read(completion.getResponse().getContentAsString(), "$.done"))
                .isTrue();
        assertThat((String) JsonPath.read(completion.getResponse().getContentAsString(),
                "$.wishCard.smallAction")).isEqualTo(SMALL_ACTION);
        // 방금 저장된 카드 id 가 완료 응답에 실려 온다 — 클라이언트가 다음 조회를 기다리지 않고
        // 이 id 로 「해봤어요」·느낀 점을 보낼 수 있다(일기 diaryId 와 같은 결).
        int responseCardId = JsonPath.read(
                completion.getResponse().getContentAsString(), "$.wishCard.wishCardId");

        // 그리고 실제로 DB 에 남아 있어야 한다.
        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).as("감정 탐색을 거친 완주는 바람 카드를 남겨야 한다").isPresent();
        assertThat(saved.get().getTargetAction()).isEqualTo(SMALL_ACTION);
        assertThat(saved.get().getSituation()).isNotBlank();
        assertThat((long) responseCardId).isEqualTo(saved.get().getId());

        Retrospect done = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(done.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("회귀 가드 — 영속화 시점 임베딩이 실패해도 바람 카드는 남는다")
    void actionCardSurvivesEmbeddingFailure() throws Exception {
        // 완료 후 임베딩이 컬럼 차원(768)과 안 맞는 벡터를 돌려준다 → updateEmbedding 의 CAST 가
        // 터진다. 임베딩은 카드 커밋 뒤 별도 트랜잭션에서 best-effort 로 돌므로, 이 실패는 삼켜지고
        // 카드는 그대로 남아야 한다.
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(3072)));

        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        driveExplorationToCompletion(user, sessionId);

        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).as("임베딩이 실패해도 바람 카드는 영속화돼야 한다").isPresent();
        assertThat(saved.get().getTargetAction()).isEqualTo(SMALL_ACTION);
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /**
     * 시작 → 일기 작성 채팅을 분기점까지 → "감정을 더 알아볼래요"(id 1)로 감정 탐색 진입 → 감정·바람·
     * 작은 행동 3턴을 각각 첫 후보(id 1)로 고른다. 완료 턴의 응답을 돌려준다.
     */
    private MvcResult driveExplorationToCompletion(User user, long sessionId) throws Exception {
        // 일기 작성 채팅 — phase 가 diary_chat 인 동안 자유 텍스트 턴을 이어간다(엔진은 6턴에서 분기점).
        String phase = "diary_chat";
        for (int i = 0; i < 10 && "diary_chat".equals(phase); i++) {
            MvcResult turn = mockMvc.perform(
                            message(user, sessionId, "{\"content\":\"팀원이 말을 끊어서 속상했어요.\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            phase = JsonPath.read(turn.getResponse().getContentAsString(), "$.phase");
        }
        assertThat(phase).isEqualTo("await_branch");

        // 분기점 → 감정 탐색 진입(id 1), 이후 감정 탐색이 끝날 때까지 첫 후보(id 1)를 고른다.
        MvcResult last = mockMvc.perform(message(user, sessionId, "{\"optionIds\":[\"1\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        phase = JsonPath.read(last.getResponse().getContentAsString(), "$.phase");
        for (int i = 0; i < 5 && "emotion_exploration".equals(phase); i++) {
            last = mockMvc.perform(message(user, sessionId, "{\"optionIds\":[\"1\"]}"))
                    .andExpect(status().isOk())
                    .andReturn();
            phase = JsonPath.read(last.getResponse().getContentAsString(), "$.phase");
        }
        assertThat(phase).isEqualTo("complete");
        return last;
    }

    private long startSession(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/retrospect")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schedules":[{"name":"면접 스터디","emotion":"anxious"}],
                                 "nickname":"정민"}"""))
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

    /** 한 축만 1인 단위 벡터 float[] — 차원으로 컬럼 적합성을 시험한다. */
    private static float[] unitVector(int dim) {
        float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }
}
