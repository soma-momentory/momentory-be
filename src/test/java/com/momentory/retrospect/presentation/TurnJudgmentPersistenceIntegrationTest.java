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
import com.momentory.retrospect.application.DispositionStart;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.disposition.DispositionArm;
import com.momentory.retrospect.domain.disposition.DispositionAssignment;
import com.momentory.retrospect.domain.disposition.StartingDisposition;
import com.momentory.retrospect.domain.disposition.TurnObservation;
import com.momentory.retrospect.domain.strategy.StrategySignals;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecord;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecordRepository;
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
    @Autowired DispositionObservationRecordRepository dispositionObservations;
    @Autowired DispositionStart dispositionStart;
    @Autowired RetrospectStateCodec codec;
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
        applyDispositionFor(DispositionAssignment.Mode.OFF, Set.of());
        strategyForEveryone(false);
    }

    private void strategyForEveryone(boolean on) {
        Object service = AopTestUtils.getTargetObject(retrospectService);
        ReflectionTestUtils.setField(service, "strategyAllUsers", on);
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
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("none", "none")));
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
        // 모델이 쓴 질문이 나간 턴만 탐색 질문으로 남는다 — 다른 기억을 묻는 고정 질문은 아니다.
        assertThat(rows).extracting(TurnJudgmentRecord::getQuestionSent).containsExactly(true, false);
        // 첫 이야기와 그 이야기를 이어 간 답변은 새 이야기가 아니다.
        assertThat(rows).extracting(TurnJudgmentRecord::getNewTopic).containsExactly(false, false);
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
        TurnJudgmentRecord failedTurn = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId).getLast();
        assertThat(failedTurn.isGenerationFailed()).isTrue();
        // 새 이야기였는지는 문장 생성 모델이 판단하므로, 생성이 실패한 턴은 알 수 없음(null)으로 남는다.
        assertThat(failedTurn.getNewTopic()).isNull();

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
    @DisplayName("전략 대화는 턴마다 성향 신호를 세어 대화별 관찰 한 줄에 더한다")
    void accumulatesDispositionSignalsPerConversation() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        long sessionId = startSession(user);

        // 1턴: 바바가 기분을 묻는다.
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("none", "none")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "말이 끊기셨군요.", "그때 기분이 어땠어요?", false, false, "면접 스터디", "none", List.of(), true)));
        say(user, sessionId, "팀원이 말을 끊었어요.");
        // 2턴: 감정을 분명히 말했고, 들어 달라고 했다. 바바는 사실을 묻는다.
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("listen", "clear")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "속상하셨군요.", "", false, false, "면접 스터디", "none", List.of(), false)));
        say(user, sessionId, "속상했어요. 그냥 들어줘요.");

        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId))
                .extracting(TurnJudgmentRecord::getAskedFeeling).containsExactly(true, false);
        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.getUserId()).isEqualTo(user.getId());
        assertThat(observation.getObservedTurns()).isEqualTo(2);
        assertThat(observation.getFeelingQuestionCount()).isEqualTo(1);
        assertThat(observation.getFeelingExpressedCount()).isEqualTo(1);
        assertThat(observation.getFeelingAvoidedCount()).isZero();
        assertThat(observation.getListenRequestCount()).isEqualTo(1);
        assertThat(observation.getOtherViewRequestCount()).isZero();
        assertThat(observation.getResistanceCount()).isZero();
        assertThat(observation.isAwaitingFeelingAnswer()).isFalse();
        assertThat(observation.getPolicyVersion()).isEqualTo(StrategyPolicy.VERSION);
        // 발화 원문은 관찰에도 없다.
        assertThat(jdbc.queryForObject("""
                select count(*) from retrospect_disposition_observations o
                where o.retrospect_id = ? and o::text like '%속상%'""", Long.class, sessionId)).isZero();
    }

    @Test
    @DisplayName("성향 반영은 기본으로 꺼져 있다 — 지난 대화에 신호가 있어도 기본값에서 시작하고 집단은 NONE 이다")
    void startsFromDefaultsWhileApplyingIsOff() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        pastConversationWithResistance(user, 3);
        pastConversationWithResistance(user, 2);

        long sessionId = startSession(user);

        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.getApplyArm()).isEqualTo(DispositionArm.NONE);
        assertThat(observation.isDerivedSelfLed()).isFalse();
        assertThat(observation.getObservedTurns()).isZero();
        assertThat(startedSignals(sessionId)).isEqualTo(StrategySignals.initial());
    }

    @Test
    @DisplayName("반영을 켠 계정은 지난 대화에서 되풀이된 신호로 시작하고, 집단과 도출된 성향이 남는다")
    void startsFromTheStoredDispositionForATreatedUser() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("none", "none")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "말이 끊기셨군요.", "속상하셨을까요, 아니면 당황하셨을까요?", false, false, "면접 스터디", "none",
                List.of(), true)));
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        pastConversationWithResistance(user, 3);
        pastConversationWithResistance(user, 2);
        // 시작만 하고 떠난 대화는 세지 않는다.
        dispositionObservations.saveAndFlush(DispositionObservationRecord.start(user.getId(),
                pastRetrospect(user), 0.5, StrategyPolicy.VERSION));

        long sessionId = startSession(user);
        say(user, sessionId, "팀원이 말을 끊었어요.");

        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.getApplyArm()).isEqualTo(DispositionArm.TREATMENT);
        assertThat(observation.isDerivedSelfLed()).isTrue();
        assertThat(observation.isDerivedOtherView()).isFalse();
        // 시작할 때 연 줄에 이번 대화의 신호가 더해진다. 미리 채운 저항은 이번 대화의 관찰로 세지 않는다.
        assertThat(observation.getObservedTurns()).isEqualTo(1);
        assertThat(observation.getResistanceCount()).isZero();
        // 첫 답변부터 고를 수 있는 형태로 묻는다.
        TurnJudgmentRecord first = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId).getFirst();
        assertThat(first.getQuestionFocus()).isEqualTo("OPEN");
        assertThat(first.getQuestionAsChoices()).isTrue();
    }

    @Test
    @DisplayName("대조 집단은 도출된 성향을 남기되 기본값에서 시작한다")
    void controlKeepsDefaultsButRecordsWhatWasDerived() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.RANDOM, Set.of());
        DispositionArm arm = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of())
                .armOf(user.getId());
        pastConversationWithResistance(user, 2);
        pastConversationWithResistance(user, 2);

        long sessionId = startSession(user);

        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.getApplyArm()).isEqualTo(arm);
        assertThat(observation.isDerivedSelfLed()).isTrue();
        assertThat(startedSignals(sessionId).resistanceCount()).isEqualTo(arm == DispositionArm.TREATMENT ? 2 : 0);
    }

    @Test
    @DisplayName("다른 관점 요청은 완료 회고 한 건부터 반영한다")
    void oneOtherViewRequestIsAlwaysReflected() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        conversationAskingOtherView(user, DispositionArm.TREATMENT, 1);

        long sessionId = startSession(user);

        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.isDerivedOtherView()).isTrue();
        assertThat(startedSignals(sessionId).explicitMode()).isEqualTo(StrategySignals.OTHER_VIEW);
    }

    @Test
    @DisplayName("기간과 반복 횟수에 상관없이 매번 과거 성향을 반영한다")
    void otherViewDoesNotNeedToRepeatAfterTheEarlyDays() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        conversationAskingOtherView(user, DispositionArm.TREATMENT, 4);

        long onceAsked = startSession(user);
        assertThat(dispositionObservations.findByRetrospectId(onceAsked).orElseThrow().isDerivedOtherView())
                .isTrue();
        assertThat(startedSignals(onceAsked).explicitMode()).isEqualTo(StrategySignals.OTHER_VIEW);

        conversationAskingOtherView(user, DispositionArm.TREATMENT, 4);
        long twiceAsked = startSession(user);
        assertThat(dispositionObservations.findByRetrospectId(twiceAsked).orElseThrow().isDerivedOtherView())
                .isTrue();
    }

    @Test
    @DisplayName("실험 전 완료된 회고도 시작 성향에 반영한다")
    void completedConversationsBeforeTheExperimentAreAlsoReflected() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        conversationAskingOtherView(user, DispositionArm.NONE, 10);

        long sessionId = startSession(user);

        assertThat(dispositionObservations.findByRetrospectId(sessionId).orElseThrow().isDerivedOtherView()).isTrue();
    }

    @Test
    @DisplayName("과거 대조군에서 완료한 회고의 관찰도 도출에 사용한다")
    void aCompletedControlConversationIsAlsoHistory() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        conversationAskingOtherView(user, DispositionArm.CONTROL, 4);

        long sessionId = startSession(user);

        assertThat(dispositionObservations.findByRetrospectId(sessionId).orElseThrow().isDerivedOtherView()).isTrue();
    }

    @Test
    @DisplayName("주도성과 다른 관점은 함께 반영된다")
    void initiativeAndOtherViewAreAppliedTogether() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        conversationAskingOtherView(user, DispositionArm.TREATMENT, 4);
        pastConversationWithResistance(user, 2);

        long sessionId = startSession(user);

        DispositionObservationRecord observation = dispositionObservations.findByRetrospectId(sessionId)
                .orElseThrow();
        assertThat(observation.isDerivedSelfLed()).isTrue();
        assertThat(observation.isDerivedOtherView()).isTrue();
    }

    @Test
    @DisplayName("전체에 켜면 목록에 없는 계정도 전략 대화를 탄다")
    void turnsOnForEveryoneWhenAllUsersIsSet() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(told("stage_3")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "말이 끊기셨군요.", "그때 기분이 어땠어요?", false, false, "면접 스터디", "none", List.of(), true)));
        User user = userRepository.saveAndFlush(User.create());
        strategyForEveryone(true);
        long sessionId = startSession(user);

        say(user, sessionId, "팀원이 말을 끊었어요.");

        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId))
                .extracting(TurnJudgmentRecord::getStrategyType).containsExactly("EXPLORE");
        assertThat(dispositionObservations.findByRetrospectId(sessionId)).isPresent();
        verify(diaryChatAssistant, never()).turn(any(), anyString());
    }

    @Test
    @DisplayName("탐색 질문 뒤에 새 이야기를 꺼낸 답변은 새 이야기로 남고, 판단 모델의 반응은 그대로 남는다")
    void recordsThatAnAnswerStartedANewStory() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("none", "none")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "발표를 하셨군요.", "발표했을 때 기분이 어땠어요?", false, false, "발표", "none", List.of())));
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        long sessionId = startSession(user);
        say(user, sessionId, "오늘 발표했어요.");

        // 기분 질문에 답하지 않고 다른 일을 꺼냈다 — 판단 모델은 「다른 얘기로 넘어감」, 문장 생성 모델은 새 이야기로 본다.
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(reacting("no_answer_or_topic_change")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "친구와 저녁을 드셨군요.", "저녁을 먹으면서 기분이 어땠어요?", true, false, "친구와의 저녁", "none",
                List.of())));
        say(user, sessionId, "아 맞다 오늘 친구랑 저녁 먹었어요.");

        List<TurnJudgmentRecord> rows = judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId);
        assertThat(rows).extracting(TurnJudgmentRecord::getNewTopic).containsExactly(false, true);
        // 반응 칸은 판단 모델의 답 그대로다 — 저항인지는 새 이야기 칸과 함께 읽는다.
        assertThat(rows).extracting(TurnJudgmentRecord::getReaction)
                .containsExactly("agree_or_elaborate", "no_answer_or_topic_change");
        assertThat(rows).extracting(TurnJudgmentRecord::getQuestionSent).containsExactly(true, true);
        assertThat(jdbc.queryForList("""
                select new_topic from retrospect_turn_judgments where retrospect_id = ? order by diary_turn""",
                Boolean.class, sessionId)).containsExactly(false, true);
    }

    @Test
    @DisplayName("판단만 기록하는 계정은 성향 관찰을 남기지 않는다")
    void doesNotObserveDispositionWithoutStrategy() throws Exception {
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(told("stage_3")));
        User user = userRepository.saveAndFlush(User.create());
        judgeFor(Set.of(user.getId()));
        long sessionId = startSession(user);

        say(user, sessionId, "팀원이 말을 끊어서 속상했어요.");

        assertThat(judgmentRecords.findByRetrospectIdOrderByDiaryTurn(sessionId)).hasSize(1)
                .allSatisfy(row -> {
                    assertThat(row.getAskedFeeling()).isNull();
                    assertThat(row.getQuestionSent()).isNull();
                    assertThat(row.getNewTopic()).isNull();
                });
        assertThat(dispositionObservations.findByRetrospectId(sessionId)).isEmpty();
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

    private void applyDispositionFor(DispositionAssignment.Mode mode, Set<Long> userIds) {
        ReflectionTestUtils.setField(dispositionStart, "assignment", new DispositionAssignment(mode, userIds));
    }

    private Long pastRetrospect(User user) {
        return retrospectRepository.saveAndFlush(Retrospect.start(user.getId(), RetrospectStatus.COMPLETED, null,
                "{}")).getId();
    }

    /** 저항이 그만큼 나온 지난 대화의 관찰 한 줄. */
    private void pastConversationWithResistance(User user, int resistance) {
        DispositionObservationRecord past = DispositionObservationRecord.start(user.getId(), pastRetrospect(user),
                0.5, StrategyPolicy.VERSION);
        for (int i = 0; i < resistance; i++) {
            past.add(new TurnObservation(true, false, false, false, false, false), StrategyPolicy.VERSION);
        }
        dispositionObservations.saveAndFlush(past);
    }

    /** 며칠 전 그 집단으로 시작해 다른 관점을 요청한 지난 대화의 관찰 한 줄. */
    private void conversationAskingOtherView(User user, DispositionArm arm, int daysAgo) {
        Long retrospectId = pastRetrospect(user);
        DispositionObservationRecord past = DispositionObservationRecord.start(user.getId(), retrospectId, 0.5,
                StrategyPolicy.VERSION, arm, StartingDisposition.NONE);
        past.add(new TurnObservation(false, false, true, false, false, false), StrategyPolicy.VERSION);
        dispositionObservations.saveAndFlush(past);
        jdbc.update("update retrospect_disposition_observations set created_at = now() - make_interval(days => ?)"
                + " where retrospect_id = ?", daysAgo, retrospectId);
    }

    private StrategySignals startedSignals(long sessionId) {
        return codec.deserialize(retrospectRepository.findById(sessionId).orElseThrow().getStateJson())
                .strategySignals();
    }

    /** 요청한 응답 방식과 감정 표현만 바꾼 판단. */
    private static TurnJudgment asking(String requested, String emotion) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        Map<JudgmentItem, String> choices = Map.of(
                JudgmentItem.END_INTENT, "continue", JudgmentItem.REQUESTED_MODE, requested,
                JudgmentItem.EMOTION_EXPRESSION, emotion, JudgmentItem.EVENT_INFO, "specific",
                JudgmentItem.REACTION, "agree_or_elaborate", JudgmentItem.EXPERIENCING_DEPTH, "stage_3");
        choices.forEach((item, choice) -> items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9))));
        return new TurnJudgment("jev-1.14.0", "v3", items, 1400, 350);
    }

    /** 바바의 직전 말에 주어진 반응을 보인 답변의 판단. */
    private static TurnJudgment reacting(String reaction) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        Map<JudgmentItem, String> choices = Map.of(
                JudgmentItem.END_INTENT, "continue", JudgmentItem.REQUESTED_MODE, "none",
                JudgmentItem.EMOTION_EXPRESSION, "none", JudgmentItem.EVENT_INFO, "specific",
                JudgmentItem.REACTION, reaction, JudgmentItem.EXPERIENCING_DEPTH, "stage_1_2");
        choices.forEach((item, choice) -> items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9))));
        return new TurnJudgment("jev-1.14.0", "v3", items, 1400, 350);
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

    @Test
    void persistsAllThreeDimensionsAndAcceptsAnImmediateChangeAfterResuming() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        var history = DispositionObservationRecord.start(user.getId(), pastRetrospect(user), 0.5, StrategyPolicy.VERSION);
        history.add(new TurnObservation(false, false, false, false, false, true), StrategyPolicy.VERSION);
        history.add(new TurnObservation(true, true, false, false, true, false), StrategyPolicy.VERSION);
        dispositionObservations.saveAndFlush(history);
        long session = startSession(user);
        var observation = dispositionObservations.findByRetrospectId(session).orElseThrow();
        assertThat(observation.isDerivedSelfLed()).isTrue();
        assertThat(observation.isDerivedListen()).isTrue();
        assertThat(observation.isDerivedGentleEmotion()).isTrue();
        assertThat(observation.getDerivedHistorySessions()).isEqualTo(1);
        assertThat(observation.isPastDispositionApplied()).isTrue();
        assertThat(observation.getDispositionVersion()).isEqualTo(StartingDisposition.VERSION);
        assertThat(startedSignals(session).explicitMode()).isEqualTo(StrategySignals.LISTEN);
        assertThat(startedSignals(session).gentleEmotion()).isTrue();
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("explore", "none")));
        when(strategyReplyWriter.write(any(), anyString(), any())).thenReturn(Optional.of(new StrategyReply(
                "이야기해 볼게요.", "그때 기분이 어땠어요?", false, false, "발표", "none", List.of(), true)));
        say(user, session, "오늘은 질문해 줘");
        assertThat(startedSignals(session).explicitMode()).isNull();
        assertThat(startedSignals(session).gentleEmotion()).isFalse();
        assertThat(startedSignals(session).resistanceCount()).isZero();
        when(turnJudge.judge(any(), anyString())).thenReturn(Optional.of(asking("none", "none")));
        say(user, session, "그 이야기를 계속할게요");
        assertThat(startedSignals(session).explicitMode()).isNull();
        assertThat(startedSignals(session).gentleEmotion()).isFalse();
    }

    @Test
    void aControlUserAlwaysStartsFreshEvenWithAllThreePastDimensions() throws Exception {
        User user;
        var assignment = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of());
        do {
            user = userRepository.saveAndFlush(User.create());
        } while (assignment.armOf(user.getId()) != DispositionArm.CONTROL);
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.RANDOM, Set.of());
        var history = DispositionObservationRecord.start(user.getId(), pastRetrospect(user), 0.5, StrategyPolicy.VERSION);
        history.add(new TurnObservation(false, false, false, false, false, true), StrategyPolicy.VERSION);
        history.add(new TurnObservation(true, true, false, false, true, false), StrategyPolicy.VERSION);
        dispositionObservations.saveAndFlush(history);
        for (int i = 0; i < 2; i++) {
            long session = startSession(user);
            assertThat(startedSignals(session)).isEqualTo(StrategySignals.initial());
            var observation = dispositionObservations.findByRetrospectId(session).orElseThrow();
            assertThat(observation.isDerivedListen()).isTrue();
            assertThat(observation.isDerivedGentleEmotion()).isTrue();
            assertThat(observation.isPastDispositionApplied()).isFalse();
        }
    }

    @Test
    void excludesUnfinishedAndSafetyStoppedHistoryAndUsesOnlyTheLatestFiveCompletedConversations() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        pastConversationWithResistance(user, 1);
        for (int i = 0; i < 5; i++) {
            conversationAskingOtherView(user, DispositionArm.NONE, 1);
        }
        for (var status : List.of(RetrospectStatus.IN_PROGRESS, RetrospectStatus.ENDED)) {
            var session = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(), status, null, "{}"));
            var history = DispositionObservationRecord.start(user.getId(), session.getId(), 0.5, StrategyPolicy.VERSION);
            history.add(new TurnObservation(true, true, false, false, false, false), StrategyPolicy.VERSION);
            dispositionObservations.saveAndFlush(history);
        }
        User other = userRepository.saveAndFlush(User.create());
        pastConversationWithResistance(other, 3);
        long session = startSession(user);
        var observation = dispositionObservations.findByRetrospectId(session).orElseThrow();
        assertThat(observation.getDerivedHistorySessions()).isEqualTo(5);
        assertThat(observation.isDerivedSelfLed()).isFalse();
        assertThat(observation.isDerivedListen()).isFalse();
        assertThat(observation.isDerivedOtherView()).isTrue();
    }

    @Test
    void analysisIncludesUnansweredHistorySessionsAndSeparatesLegacyVersions() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        strategyFor(Set.of(user.getId()));
        applyDispositionFor(DispositionAssignment.Mode.LISTED, Set.of(user.getId()));
        pastConversationWithResistance(user, 1);
        long unanswered = startSession(user);
        jdbc.update("update retrospects set created_at = now() - interval '2 days' where id = ?", unanswered);
        String setup = java.nio.file.Files.readString(java.nio.file.Path.of("eval/strategy-metrics/00_setup.sql"));
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(setup);
                try (var result = statement.executeQuery("select analysis_target, dropout_status, explore_questions "
                        + "from sm_session_metrics where retrospect_id = " + unanswered)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.getString(2)).isEqualTo("DROPOUT");
                    assertThat(result.getInt(3)).isZero();
                }
                for (String script : List.of("10_check_logging.sql", "20_progress.sql", "30_metrics.sql")) {
                    try {
                        statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of("eval/strategy-metrics", script)));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
                statement.execute("update retrospect_disposition_observations set disposition_version = 'conditional-v1'"
                        + " where retrospect_id = " + unanswered);
                try (var result = statement.executeQuery("select count(*) from sm_session_metrics where retrospect_id = "
                        + unanswered)) {
                    result.next();
                    assertThat(result.getInt(1)).isZero();
                }
                statement.execute("drop view pg_temp.sm_params cascade");
            }
            return null;
        });
    }

}
