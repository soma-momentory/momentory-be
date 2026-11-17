package com.momentory.retrospect.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.application.RetrospectCompleted;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 행동(바람) 카드 영속화 (채팅흐름_v2).
 *
 * <p>감정 · 바람을 다시 묻던 탐색 단계를 없애면서 회고는 바람 카드를 더 만들지 않는다 — 실제 HTTP 로 회고를 끝까지
 * 몰아 일기만 남고 카드는 남지 않는지 본다. 카드를 실은 완료 이벤트를 받으면 리스너가 카드를 저장하고, 그 저장이 상황
 * 임베딩 실패에 얽매이지 않는지(임베딩은 커밋 뒤 best-effort)는 이벤트를 직접 내서 지킨다.
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
    @Autowired DiaryRepository diaryRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean DiaryChatAssistant diaryChatAssistant;
    @MockitoBean EmotionExtractor emotionExtractor;
    @MockitoBean DiaryWriter diaryWriter;
    @MockitoBean SituationEmbedder situationEmbedder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        when(diaryChatAssistant.turn(any(), anyString())).thenReturn(Optional.of(new DiaryTurn(
                "면접 스터디에서 팀원이 말을 끊었다", List.of(), "내 의견이 가볍게 다뤄진 게 걸린다", true,
                "조금 더 들려줄래요?", null, "none", List.of(), false, false, false, false, false)));
        // 대화 끝 감정 추출 — 일기 감정 태그가 된다.
        when(emotionExtractor.extract(any())).thenReturn(new EmotionExtraction(
                List.of(new ExtractedEvent(1, "면접 스터디", "면접 스터디에서 팀원이 말을 끊었다", List.of(1))),
                List.of(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, 3,
                        EmotionPhase.DURING, "말을 끊겼어요", List.of(1))),
                null));
        when(diaryWriter.write(any())).thenReturn(Optional.of(new DiaryOutput("그냥 일기.", null)));
    }

    @Test
    @DisplayName("회고를 끝까지 해도 바람 카드는 만들지 않는다 — 일기만 남는다")
    void completionLeavesDiaryButNoActionCard() throws Exception {
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(768)));
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        // 일기 작성 채팅 — phase 가 diary_chat 인 동안 자유 텍스트 턴을 이어간다(엔진은 5턴에서 마무리한다).
        MvcResult last = null;
        String phase = "diary_chat";
        for (int i = 0; i < 10 && "diary_chat".equals(phase); i++) {
            last = mockMvc.perform(
                            message(user, sessionId, "{\"content\":\"팀원이 말을 끊어서 속상했어요.\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            phase = JsonPath.read(last.getResponse().getContentAsString(), "$.phase");
        }

        String body = last.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(phase).isEqualTo("complete");
        assertThat((boolean) JsonPath.read(body, "$.done")).isTrue();
        assertThat((String) JsonPath.read(body, "$.diary.diary")).isEqualTo("그냥 일기.");
        assertThat(body).doesNotContain("wishCard\":{");
        assertThat(actionCardRepository.findByRetrospectId(sessionId)).isEmpty();
        assertThat(diaryRepository.findByRetrospectId(sessionId)).isPresent();
        Retrospect done = retrospectRepository.findById(sessionId).orElseThrow();
        assertThat(done.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("대조군 — 카드를 실은 완료 이벤트를 받으면 리스너가 카드를 저장한다")
    void listenerPersistsCardFromCompletionEvent() throws Exception {
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(768)));
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        publishCompletionWithCard(user, sessionId);

        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getSituation()).isEqualTo("면접 스터디에서 팀원이 말을 끊었다");
        assertThat(saved.get().getTargetAction()).isNull();
    }

    @Test
    @DisplayName("회귀 가드 — 영속화 시점 임베딩이 실패해도 바람 카드는 남는다")
    void actionCardSurvivesEmbeddingFailure() throws Exception {
        // 임베딩이 컬럼 차원(768)과 안 맞는 벡터를 돌려준다 → updateEmbedding 의 CAST 가 터진다. 임베딩은 카드
        // 커밋 뒤 별도 트랜잭션에서 best-effort 로 돌므로, 이 실패는 삼켜지고 카드는 그대로 남아야 한다.
        when(situationEmbedder.embed(anyString())).thenReturn(Optional.of(unitVector(3072)));
        User user = userRepository.saveAndFlush(User.create());
        long sessionId = startSession(user);

        publishCompletionWithCard(user, sessionId);

        Optional<ActionCard> saved = actionCardRepository.findByRetrospectId(sessionId);
        assertThat(saved).as("임베딩이 실패해도 바람 카드는 영속화돼야 한다").isPresent();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** 회고 완료 턴과 같이 트랜잭션 안에서 카드를 실은 완료 이벤트를 낸다 — 임베딩은 커밋 뒤에 돈다. */
    private void publishCompletionWithCard(User user, long sessionId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> events.publishEvent(
                new RetrospectCompleted(sessionId, user.getId(), null,
                        new RetrospectCompleted.WishCardData("면접 스터디에서 팀원이 말을 끊었다",
                                List.of(Emotion.ANGRY), List.of("존중"), null, null, "negative"),
                        List.of())));
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
