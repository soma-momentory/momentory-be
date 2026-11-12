package com.momentory.retrospect.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.retrospect.application.RetrospectService;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.StrategyReply;
import com.momentory.retrospect.domain.assistant.StrategyReplyWriter;
import com.momentory.retrospect.domain.assistant.TurnJudge;
import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.TurnJudgmentRecord;
import com.momentory.retrospect.infrastructure.persistence.TurnJudgmentRecordRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 턴 판단 기록의 영속화 — 대상 계정의 일기 작성 채팅 답변마다 한 줄이 남고, 대상이 아닌 계정은 판단 모델을
 * 부르지도 기록하지도 않는다. 판단이 실패한 턴도 실패로 한 줄 남고 대화는 이어진다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class TurnJudgmentPersistenceIntegrationTest {

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
    @Autowired TurnJudgmentRecordRepository judgmentRecords;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired RetrospectService retrospectService;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean DiaryChatAssistant diaryChatAssistant;
    @MockitoBean TurnJudge turnJudge;
    @MockitoBean StrategyReplyWriter strategyReplyWriter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        when(diaryChatAssistant.turn(any(), anyString())).thenReturn(Optional.of(new DiaryTurn(
                "면접 스터디에서 팀원이 말을 끊었다", List.of(), null, true,
                "그때 어떤 기분이 드셨나요?", "말이 끊겨 당황하셨겠어요.", "none", List.of(),
                false, false, false, false, false)));
    }

    @AfterEach
    void resetJudgeUsers() {
        judgeFor(Set.of());
        strategyFor(Set.of());
    }

    private void strategyFor(Set<Long> userIds) {
        Object service = AopTestUtils.getTargetObject(retrospectService);
        ReflectionTestUtils.setField(service, "strategyUserIds", userIds);
    }

    /** 대상 계정 목록은 기동 설정값이라, 방금 만든 사용자 id 로 바꿔 끼운다. */
    private void judgeFor(Set<Long> userIds) {
        Object service = AopTestUtils.getTargetObject(retrospectService);
        ReflectionTestUtils.setField(service, "judgeUserIds", userIds);
    }

    @Test
    @DisplayName("대상 계정의 답변마다 판단 한 줄이 남고, 발화 원문은 기록에 없다")
    void recordsEachJudgedTurn() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(judgment("stage_3")));
        User user = userRepository.saveAndFlush(User.create());
        judgeFor(Set.of(user.getId()));
        long sessionId = startSession(user);

        say(user, sessionId, "팀원이 말을 끊어서 속상했어요.");
        say(user, sessionId, "제 의견이 가볍게 다뤄진 것 같았어요.");

        List<TurnJudgmentRecord> rows = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId);
        assertThat(rows).extracting(TurnJudgmentRecord::getDiaryTurn).containsExactly(1, 2);
        TurnJudgmentRecord first = rows.getFirst();
        assertThat(first.getUserId()).isEqualTo(user.getId());
        assertThat(first.getStatus()).isEqualTo("JUDGED");
        assertThat(first.getModel()).isEqualTo("jev-1.14.0");
        assertThat(first.getQuestionsVersion()).isEqualTo("v1");
        assertThat(first.getExperiencingDepth()).isEqualTo("stage_3");
        assertThat(first.getEndIntent()).isEqualTo("end_request");
        assertThat(first.getEndIntentConfidence()).isEqualTo(0.9);
        assertThat(first.getInputTokens()).isEqualTo(1400);
        assertThat(first.getLatencyMs()).isEqualTo(350);
        // 판단만 켠 계정은 전략 칸이 비어 있고 대화도 기존 순서 그대로다.
        assertThat(first.getStrategyType()).isNull();
        assertThat(first.getStrategyApplied()).isNull();
        assertThat((Double) JsonPath.read(first.getProbabilitiesJson(), "$.experiencing_depth.stage_3"))
                .isEqualTo(0.9);
        Map<String, Object> probabilities = JsonPath.read(first.getProbabilitiesJson(), "$");
        assertThat(probabilities).containsOnlyKeys("end_intent", "requested_mode", "emotion_expression", "event_info",
                        "reaction", "experiencing_depth");
        // 판단 기록 어디에도 발화 원문이 없다 — 메시지 id 로만 가리킨다.
        assertThat(first.getMessageId()).matches("m\\d+");
        assertThat(jdbc.queryForObject("""
                select count(*) from retrospect_turn_judgments t
                where t.retrospect_id = ? and t::text like '%속상%'""", Long.class, sessionId)).isZero();
    }

    @Test
    @DisplayName("전략 선택 대상 계정은 방침이 판단 옆에 남고, 문장 생성 모델이 쓴 말이 나간다")
    void recordsTheChosenCourseAndUsesTheWriter() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(told("stage_3")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "말이 끊겨 당황하셨군요.", "그때 스스로한테는 어떤 마음이 드셨어요?", false, false, "면접 스터디", "none",
                List.of())));
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        long sessionId = startSession(user);

        MvcResult first = say(user, sessionId, "팀원이 말을 끊어서 속상했어요.");

        assertThat((String) JsonPath.read(first.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.text")).isEqualTo("말이 끊겨 당황하셨군요.\n그때 스스로한테는 어떤 마음이 드셨어요?");
        // 전략 대화는 옛 일기 작성 턴 모델을 부르지 않는다.
        verify(diaryChatAssistant, never()).turn(any(), anyString());

        // 문장 생성 모델이 더 물을 것이 없다고 하면 다른 기억을 묻는다.
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "계속 마음에 남으셨군요.", "", false, true, "면접 스터디", "none", List.of())));
        MvcResult moved = say(user, sessionId, "계속 마음에 남아요.");
        assertThat((String) JsonPath.read(moved.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.text")).isEqualTo("계속 마음에 남으셨군요.\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");

        List<TurnJudgmentRecord> rows = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId);
        assertThat(rows).extracting(TurnJudgmentRecord::getStrategyType).containsExactly("EXPLORE", "EXPLORE");
        assertThat(rows).extracting(TurnJudgmentRecord::getQuestionFocus).containsExactly("OPEN", "OTHER_TOPIC");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getPolicyVersion()).isEqualTo(StrategyPolicy.VERSION);
            assertThat(row.getStrategyApplied()).isTrue();
            assertThat(row.getQuestionAsChoices()).isFalse();
            assertThat(row.isGenerationFailed()).isFalse();
            // 체험 깊이는 방침에 쓰이지 않지만 측정값으로 남는다.
            assertThat(row.getExperiencingDepth()).isEqualTo("stage_3");
        });

        // 문장 생성 모델이 실패한 턴은 기록에서 가려낼 수 있다.
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.empty());
        say(user, sessionId, "그 생각이 자꾸 나요.");
        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId).getLast().isGenerationFailed())
                .isTrue();

        // 판단 모델이 실패한 턴에도 방침은 남는다 — 옛 고정 순서로 돌아가지 않는다.
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.empty());
        say(user, sessionId, "아직도 마음이 무거워요.");
        TurnJudgmentRecord failed = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId).getLast();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getStrategyType()).isNotNull();
        assertThat(failed.getPolicyVersion()).isEqualTo(StrategyPolicy.VERSION);
        assertThat(failed.getExperiencingDepth()).isNull();
    }

    @Test
    @DisplayName("대상이 아닌 계정은 판단 모델을 부르지 않고 기록도 남기지 않는다")
    void doesNothingForOtherUsers() throws Exception {
        User judged = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        judgeFor(Set.of(judged.getId()));
        long sessionId = startSession(other);

        say(other, sessionId, "팀원이 말을 끊어서 속상했어요.");

        verify(turnJudge, never()).judge(any(), anyString());
        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("판단이 실패한 턴은 실패로 한 줄 남고 대화는 이어진다")
    void recordsFailedTurn() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.empty());
        User user = userRepository.saveAndFlush(User.create());
        judgeFor(Set.of(user.getId()));
        long sessionId = startSession(user);

        MvcResult turn = say(user, sessionId, "팀원이 말을 끊어서 속상했어요.");

        assertThat((String) JsonPath.read(turn.getResponse().getContentAsString(), "$.phase"))
                .isEqualTo("diary_chat");
        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo("FAILED");
                    assertThat(row.getModel()).isNull();
                    assertThat(row.getExperiencingDepth()).isNull();
                    assertThat(row.getProbabilitiesJson()).isNull();
                });
    }

    @Test
    @DisplayName("회고가 지워지면 판단 기록도 함께 지워진다")
    void deletesWithTheRetrospect() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(judgment("stage_4")));
        User user = userRepository.saveAndFlush(User.create());
        judgeFor(Set.of(user.getId()));
        long sessionId = startSession(user);
        say(user, sessionId, "팀원이 말을 끊어서 속상했어요.");
        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId)).hasSize(1);

        jdbc.update("delete from retrospects where id = ?", sessionId);

        assertThat(jdbc.queryForObject(
                "select count(*) from retrospect_turn_judgments where retrospect_id = ?", Long.class,
                sessionId)).isZero();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private static TurnJudgment judgment(String depth) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        for (JudgmentItem item : JudgmentItem.values()) {
            String choice = item == JudgmentItem.EXPERIENCING_DEPTH ? depth : item.choices().getFirst();
            items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9, "undeterminable", 0.1)));
        }
        return new TurnJudgment("jev-1.14.0", "v1", items, 1400, 350);
    }

    /** 감정과 사건이 다 나온 답변의 판단. */
    private static TurnJudgment told(String depth) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        Map<JudgmentItem, String> choices = Map.of(
                JudgmentItem.END_INTENT, "continue", JudgmentItem.REQUESTED_MODE, "none",
                JudgmentItem.EMOTION_EXPRESSION, "clear", JudgmentItem.EVENT_INFO, "specific",
                JudgmentItem.REACTION, "agree_or_elaborate", JudgmentItem.EXPERIENCING_DEPTH, depth);
        choices.forEach((item, choice) -> items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9))));
        return new TurnJudgment("jev-1.14.0", "v1", items, 1400, 350);
    }

    private MvcResult say(User user, long sessionId, String text) throws Exception {
        return mockMvc.perform(post("/api/v1/retrospect/{id}/messages", sessionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
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

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
