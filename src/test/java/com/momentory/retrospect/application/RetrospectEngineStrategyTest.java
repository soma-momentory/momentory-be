package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

/** 전략 선택을 켠 계정은 판단으로 고른 전략대로 응답이 나가고, 판단이 없으면 기존 고정 순서로 간다. */
class RetrospectEngineStrategyTest {

    private static final String EMPATHY = "팀장님께 또 지적을 받으셨군요.";

    private FakeAssistant fake;
    private RetrospectEngine engine;
    private RetrospectState state;
    private final Deque<Optional<TurnJudgment>> judgments = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        fake = new FakeAssistant();
        fake.turnEvent = "팀장에게 지적받은 일";
        fake.turnEmpathy = EMPATHY;
        engine = new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake,
                (s, text) -> judgments.isEmpty() ? Optional.empty() : judgments.poll(),
                new StrategyPolicy(0.5, 0.8, 2), new LlmUsageLogger(0.10, 0.40), e -> { }, 1, 3);
        state = new RetrospectState("s1");
        engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        state.enableTurnStrategy();
    }

    /** 기본은 '계속 · 요청 없음 · 동의', 확신 0.9. 감정 · 사건 · 깊이만 바꿔 쓴다. */
    private static TurnJudgment judgment(String emotion, String event, String depth) {
        return judgment("continue", emotion, event, depth);
    }

    private static TurnJudgment judgment(String end, String emotion, String event, String depth) {
        return judgment(end, "none", emotion, event, depth);
    }

    private static TurnJudgment judgment(String end, String requested, String emotion, String event,
            String depth) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        put(items, JudgmentItem.END_INTENT, end);
        put(items, JudgmentItem.REQUESTED_MODE, requested);
        put(items, JudgmentItem.EMOTION_EXPRESSION, emotion);
        put(items, JudgmentItem.EVENT_INFO, event);
        put(items, JudgmentItem.REACTION, "agree_or_elaborate");
        put(items, JudgmentItem.EXPERIENCING_DEPTH, depth);
        return new TurnJudgment("jev-test", "v1", items, 0, 0);
    }

    private static void put(Map<JudgmentItem, ItemJudgment> items, JudgmentItem item, String choice) {
        items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9)));
    }

    private ReplyDto say(String text, TurnJudgment judgment) {
        judgments.add(Optional.ofNullable(judgment));
        return engine.handle(state, TurnCommand.text(text));
    }

    private TurnJudgmentLog lastLog() {
        return state.drainJudgmentLogs().getLast();
    }

    @Test
    void asksTheQuestionTheStrategyAimsAt() {
        fake.turnQuestion = "그 말을 들었을 때 정민님은 어떠셨어요?";

        ReplyDto reply = say("오늘 팀장한테 또 한 소리 들었어.", judgment("none", "specific", "stage_1_2"));

        assertThat(reply.text()).isEqualTo(EMPATHY + "\n그 말을 들었을 때 정민님은 어떠셨어요?");
        assertThat(fake.strategiesSeen).containsExactly(
                new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.SELF, false));
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy().type()).isEqualTo(StrategyType.EXPLORE);
        assertThat(log.policyVersion()).isEqualTo(StrategyPolicy.VERSION);
        assertThat(log.generationFailed()).isFalse();
        assertThat(log.strategyApplied()).isTrue();
        // 전략은 그 턴의 문장 생성에만 쓰고 남기지 않는다.
        assertThat(state.turnStrategy()).isNull();
    }

    @Test
    void onlyReflectsForAUserWhoAskedToBeHeardEvenIfTheModelWroteAQuestion() {
        fake.turnEmpathy = "발표가 뜻대로 되지 않아 허무하셨군요.";
        fake.turnQuestion = "어떤 부분이 가장 아쉬우셨나요?";

        ReplyDto reply = say("그냥 들어줘. 발표 망쳐서 허무해.",
                judgment("continue", "listen", "clear", "specific", "stage_3"));

        assertThat(reply.text()).isEqualTo("발표가 뜻대로 되지 않아 허무하셨군요.");
        assertThat(reply.phase()).isEqualTo(Phase.DIARY_CHAT.key());
    }

    @Test
    void asksEvenWhenEmotionAndEventAreAlreadyOut() {
        fake.turnEmpathy = "발표가 뜻대로 되지 않아 허무하셨군요.";
        fake.turnQuestion = "어떤 점이 제일 허무하셨어요?";

        ReplyDto reply = say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));

        assertThat(reply.text()).isEqualTo("발표가 뜻대로 되지 않아 허무하셨군요.\n어떤 점이 제일 허무하셨어요?");
    }

    @Test
    void goesStraightToTheQuestionAfterAShortAgreement() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        fake.turnEmpathy = "그렇게 동의해 주시니 저도 마음이 놓이네요.";
        fake.turnQuestion = "어떤 점이 제일 허무하셨어요?";

        ReplyDto reply = say("맞아", judgment("none", "specific", "undeterminable"));

        // 앞의 공감을 되풀이하지 않는다 — 모델이 쓴 공감 문장도 붙이지 않는다.
        assertThat(reply.text()).isEqualTo("어떤 점이 제일 허무하셨어요?");
        assertThat(fake.strategiesSeen.getLast().brief()).isTrue();
    }

    @Test
    void fallsBackToAFixedQuestionForTheFocusWhenTheModelWroteNone() {
        fake.turnQuestion = "";
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));

        ReplyDto reply = say("그냥 허무했어.", judgment("clear", "specific", "stage_3"));

        assertThat(reply.text()).isEqualTo(EMPATHY + "\n어떤 점이 가장 그렇게 느껴지셨나요?");
        assertThat(fake.strategiesSeen.getLast().focus()).isEqualTo(QuestionFocus.FEELING);
    }

    @Test
    void closesRightAwayOnAConfidentEndRequest() {
        ReplyDto reply = say("오늘은 이쯤에서 마무리하고 싶어", judgment("end_request", "none", "none", "stage_1_2"));

        assertThat(state.phase()).isEqualTo(Phase.COMPLETE);
        assertThat(reply.diary()).isNotNull();
        // 마무리는 질문을 만들 필요가 없다 — 일기 작성 턴 모델을 부르지 않는다.
        assertThat(fake.diaryTurnCalls).isZero();
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy().type()).isEqualTo(StrategyType.CLOSE);
        assertThat(log.strategyApplied()).isTrue();
    }

    @Test
    void movesToAnotherMemoryWithTheFixedQuestion() {
        say("회의가 있었어.", judgment("none", "specific", "stage_1_2"));
        say("그냥 회의였어.", judgment("none", "specific", "stage_1_2"));

        ReplyDto reply = say("별일 없었어.", judgment("none", "specific", "stage_1_2"));

        assertThat(reply.text()).endsWith("오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        assertThat(fake.strategiesSeen.getLast().focus()).isEqualTo(QuestionFocus.OTHER_TOPIC);
    }

    @Test
    void usesTheFixedOrderWhenTheJudgmentFailed() {
        RetrospectState legacy = new RetrospectState("s2");
        engine.start(legacy, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        ReplyDto expected = engine.handle(legacy, TurnCommand.text("오늘 팀장한테 또 한 소리 들었어."));

        ReplyDto reply = say("오늘 팀장한테 또 한 소리 들었어.", null);

        assertThat(reply).isEqualTo(expected);
        assertThat(fake.strategiesSeen).containsOnlyNulls();
        TurnJudgmentLog log = lastLog();
        assertThat(log.failed()).isTrue();
        assertThat(log.strategy()).isNull();
    }

    @Test
    void marksTheTurnWhenTheWordingModelFailed() {
        fake.failDiaryChat = true;

        ReplyDto reply = say("발표 망쳐서 허무해.", judgment("clear", "partial", "stage_3"));

        // 전략은 그대로 따르되 문장은 고정 문구다 — 기록에서 구분된다.
        assertThat(reply.text()).endsWith("그때 무슨 일이 있었는지 조금만 더 들려주실래요?");
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategyApplied()).isTrue();
        assertThat(log.generationFailed()).isTrue();
    }

    @Test
    void recordsTheStrategyAsNotAppliedWhenTheTurnCapEndsTheChat() {
        for (int i = 1; i < RetrospectState.DIARY_MAX_TURNS; i++) {
            say("이야기 " + i, judgment("clear", "specific", "stage_3"));
        }
        state.drainJudgmentLogs();

        say("마지막 이야기", judgment("clear", "specific", "stage_3"));

        assertThat(state.phase()).isNotEqualTo(Phase.DIARY_CHAT);
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy()).isNotNull();
        assertThat(log.strategyApplied()).isFalse();
    }

    @Test
    void keepsSessionSignalsAcrossSavedTurns() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));

        RetrospectState restored = RetrospectState.fromSnapshot(state.toSnapshot());

        assertThat(restored.strategySignals()).isEqualTo(state.strategySignals());
        assertThat(restored.strategySignals().topicDepth()).isEqualTo(3);
        assertThat(restored.strategySignals().lastType()).isEqualTo(StrategyType.EXPLORE);
        // 켜짐 여부는 요청마다 서비스가 정한다 — 저장되지 않는다.
        assertThat(restored.turnStrategyEnabled()).isFalse();
    }

    @Test
    void judgingAloneDoesNotChangeTheReply() {
        RetrospectState judgedOnly = new RetrospectState("s3");
        engine.start(judgedOnly, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        judgedOnly.enableTurnJudging();
        judgments.add(Optional.of(judgment("clear", "specific", "stage_3")));

        engine.handle(judgedOnly, TurnCommand.text("발표 망쳐서 허무해."));

        assertThat(fake.strategiesSeen).containsOnlyNulls();
        List<TurnJudgmentLog> logs = judgedOnly.drainJudgmentLogs();
        assertThat(logs).singleElement().satisfies(log -> assertThat(log.strategy()).isNull());
    }
}
