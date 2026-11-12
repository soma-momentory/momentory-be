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
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.TurnJudge;
import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

/**
 * 전략 선택을 켠 계정은 설계 문서의 흐름만 탄다 — 판단으로 고른 전략대로 응답이 나가고, 옛 고정 순서 · 5턴 상한 ·
 * 단답 체크인은 거치지 않는다.
 */
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
        engine = engine(12);
        state = new RetrospectState("s1");
        engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        state.enableTurnStrategy();
    }

    private RetrospectEngine engine(int maxTurns) {
        TurnJudge judge = (s, text) -> judgments.isEmpty() ? Optional.empty() : judgments.poll();
        LlmUsageLogger usage = new LlmUsageLogger(0.10, 0.40);
        return new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake, judge,
                new StrategyDiaryChat(judge, new StrategyPolicy(0.5, 0.8, 2, 8), fake, usage, maxTurns), usage,
                e -> { }, 1, 3);
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
        assertThat(log.policyVersion()).isEqualTo(StrategyPolicy.VERSION);
        assertThat(log.strategyApplied()).isTrue();
        assertThat(log.generationFailed()).isFalse();
        // 전략은 그 턴의 문장 생성에만 쓰고 남기지 않는다.
        assertThat(state.turnStrategy()).isNull();
    }

    @Test
    void dropsTheNameTheModelPutInFrontOfTheReplyAndTheQuestion() {
        fake.turnEmpathy = "정민님, 오늘 시험을 치르셨군요.";
        fake.turnQuestion = "정민님, 시험을 치르면서 어떤 기분이 드셨어요?";

        ReplyDto reply = say("오늘 시험 쳤는데 망한 거 같아", judgment("none", "specific", "stage_3"));

        assertThat(reply.text()).isEqualTo("오늘 시험을 치르셨군요.\n시험을 치르면서 어떤 기분이 드셨어요?");
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
    void goesStraightToTheQuestionAfterAShortAgreement() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        fake.turnEmpathy = "그렇게 동의해 주시니 저도 마음이 놓이네요.";
        fake.turnQuestion = "그때 스스로한테는 어떤 마음이 드셨어요?";

        ReplyDto reply = say("맞아", judgment("none", "specific", "undeterminable"));

        // 앞의 공감을 되풀이하지 않는다 — 모델이 쓴 공감 문장도 붙이지 않는다. 같은 것을 다시 묻지도 않는다.
        assertThat(reply.text()).isEqualTo("오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        assertThat(fake.strategiesSeen.getLast().brief()).isTrue();
    }

    @Test
    void proposesWrapUpWithChoicesAndTalksOnWhenTheUserWantsMore() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        fake.turnEmpathy = "요즘 그런 말에 유독 크게 흔들리시는군요.";

        ReplyDto proposal = say("요즘 이런 말에 유독 크게 흔들리는 것 같아.", judgment("clear", "specific", "stage_4"));

        assertThat(proposal.text()).isEqualTo("요즘 그런 말에 유독 크게 흔들리시는군요.\n여기까지 일기로 정리해볼까요?");
        assertThat(proposal.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("이제 정리할래요", "조금 더 이야기할래요");
        assertThat(proposal.phase()).isEqualTo(Phase.DIARY_CHAT.key());
        assertThat(lastLog().strategy().focus()).isEqualTo(QuestionFocus.WRAP_UP);

        // 끊지 않는다 — 더 이야기하겠다고 하면 사용자에게 맡기고, 다음 답변부터 다시 묻는다.
        ReplyDto more = engine.handle(state, TurnCommand.option("2"));
        assertThat(more.text()).isEqualTo("좋아요, 편하게 이어가 봐요. 더 나누고 싶은 이야기를 들려주세요.");
        assertThat(state.phase()).isEqualTo(Phase.DIARY_CHAT);
        fake.turnQuestion = "그 말이 유독 크게 느껴진 이유가 있을까요?";
        ReplyDto next = say("사실 예전부터 그랬어.", judgment("clear", "specific", "stage_4"));
        assertThat(next.options()).isNullOrEmpty();
        assertThat(next.text()).endsWith("그 말이 유독 크게 느껴진 이유가 있을까요?");
    }

    @Test
    void wrapsUpIntoTheDiaryWhenTheUserAcceptsTheProposal() {
        fake.events.add(new ExtractedEvent(1, "발표", "발표를 망친 일", List.of(1, 2)));
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        say("요즘 이런 말에 유독 크게 흔들리는 것 같아.", judgment("clear", "specific", "stage_4"));

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        // 일기를 만들고 감정 탐색으로 넘어간다 — 그 뒤는 이 대화의 범위가 아니다.
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
        assertThat(reply.text()).contains(RetrospectEngine.EXPLORATION_TRANSITION);
    }

    @Test
    void doesNotStopAtFiveTurnsOrShowTheOldCheckIn() {
        // 옛 엔진이라면 5턴에서 끝났고, 3턴 뒤의 짧은 답에는 「지금까지 이야기해줘서 고마워요」 정리 선택지가 떴다.
        for (int i = 1; i <= 7; i++) {
            ReplyDto reply = say("그냥", judgment("none", "none", "stage_1_2"));
            assertThat(reply.text()).as("턴 %d", i).doesNotContain("지금까지 이야기해줘서 고마워요");
            if (reply.options() != null && !reply.options().isEmpty()) {
                // 새 흐름의 마무리 제안이다 — 더 이야기하겠다고 하면 이어진다.
                assertThat(reply.text()).endsWith("여기까지 일기로 정리해볼까요?");
                engine.handle(state, TurnCommand.option("2"));
            }
        }
        assertThat(state.phase()).isEqualTo(Phase.DIARY_CHAT);
        assertThat(state.diaryTurn()).isEqualTo(7);
    }

    @Test
    void finishesAtTheSafetyCapAndRecordsThatTurnAsNotApplied() {
        engine = engine(3);
        state = new RetrospectState("capped");
        engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        state.enableTurnStrategy();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        say("이야기 1", judgment("none", "none", "stage_1_2"));
        say("이야기 2", judgment("none", "none", "stage_1_2"));
        state.drainJudgmentLogs();

        say("이야기 3", judgment("none", "none", "stage_1_2"));

        assertThat(state.phase()).isNotEqualTo(Phase.DIARY_CHAT);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy()).isNotNull();
        assertThat(log.strategyApplied()).isFalse();
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
    void movesToAnotherMemoryWithTheFixedQuestionWhenTheUserStruggles() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        fake.turnEmpathy = "딱 집어 말하기 어려우셨군요.";

        ReplyDto reply = say("그냥 전부.", struggle());

        assertThat(reply.text()).isEqualTo("딱 집어 말하기 어려우셨군요.\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        assertThat(reply.options()).isNullOrEmpty();
        assertThat(fake.strategiesSeen.getLast().focus()).isEqualTo(QuestionFocus.OTHER_TOPIC);
    }

    @Test
    void wrapsUpWhenTheUserHasNoOtherMemory() {
        fake.events.add(new ExtractedEvent(1, "발표", "발표를 망친 일", List.of(1, 2)));
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));
        say("그냥 전부.", struggle());
        int judged = fake.diaryTurnCalls;

        engine.handle(state, TurnCommand.text("없어요"));

        // 「다른 기억」 질문에 없다고 하면 지금까지의 이야기로 정리한다 — 판단도 문장 생성도 부르지 않는다.
        assertThat(fake.diaryTurnCalls).isEqualTo(judged);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
    }

    @Test
    void goesByTheDefaultWhenTheJudgmentFailedInsteadOfTheOldFixedOrder() {
        fake.turnQuestion = "그때 어떤 일이 있었는지 들려주실래요?";

        ReplyDto reply = say("오늘 팀장한테 또 한 소리 들었어.", null);

        assertThat(reply.text()).isEqualTo(EMPATHY + "\n그때 어떤 일이 있었는지 들려주실래요?");
        // 판단이 없어도 전략은 고른다 — 기본값(인정 + 질문). 모델에게도 그 전략이 간다.
        assertThat(fake.strategiesSeen).containsExactly(
                new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.EVENT, false));
        TurnJudgmentLog log = lastLog();
        assertThat(log.failed()).isTrue();
        assertThat(log.strategy()).isNotNull();
        assertThat(log.strategyApplied()).isTrue();
    }

    @Test
    void aClearStopPhraseStillClosesWhenTheJudgmentFailed() {
        say("그만할래", null);

        assertThat(state.phase()).isEqualTo(Phase.COMPLETE);
        assertThat(fake.diaryTurnCalls).isZero();
    }

    @Test
    void usesAPlainAcknowledgementAndAFixedQuestionWhenTheWordingModelFailed() {
        fake.failDiaryChat = true;

        ReplyDto reply = say("발표 망쳐서 허무해.", judgment("clear", "partial", "stage_3"));

        // 전략은 그대로 따르되 문장은 고정 문구다 — 기록에서 구분된다.
        assertThat(reply.text()).isEqualTo("네, 듣고 있어요.\n그때 무슨 일이 있었는지 조금만 더 들려주실래요?");
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategyApplied()).isTrue();
        assertThat(log.generationFailed()).isTrue();
    }

    @Test
    void stillStopsForACrisisTheWordingModelFlagged() {
        fake.turnSafetyLevel = "risk";

        ReplyDto reply = say("요즘 다 내려놓고 싶다는 생각이 들어", judgment("clear", "none", "stage_4"));

        assertThat(state.phase()).isEqualTo(Phase.SAFETY_HOLD);
        assertThat(reply.safetyLevel()).isEqualTo("risk");
    }

    @Test
    void keepsSessionSignalsAcrossSavedTurns() {
        say("발표 망쳐서 허무해.", judgment("clear", "specific", "stage_3"));

        RetrospectState restored = RetrospectState.fromSnapshot(state.toSnapshot());

        assertThat(restored.strategySignals()).isEqualTo(state.strategySignals());
        assertThat(restored.strategySignals().topicDepth()).isEqualTo(3);
        assertThat(restored.strategySignals().startDepth()).isEqualTo(3);
        assertThat(restored.strategySignals().questionsAsked()).isEqualTo(1);
        assertThat(restored.strategySignals().lastFocus()).isEqualTo(QuestionFocus.FEELING);
        // 켜짐 여부는 요청마다 서비스가 정한다 — 저장되지 않는다.
        assertThat(restored.turnStrategyEnabled()).isFalse();
    }

    @Test
    void judgingAloneKeepsTheOldFlowAndPicksNoStrategy() {
        RetrospectState judgedOnly = new RetrospectState("s3");
        engine.start(judgedOnly, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        judgedOnly.enableTurnJudging();
        judgments.add(Optional.of(judgment("clear", "specific", "stage_3")));

        engine.handle(judgedOnly, TurnCommand.text("발표 망쳐서 허무해."));

        assertThat(fake.strategiesSeen).containsOnlyNulls();
        List<TurnJudgmentLog> logs = judgedOnly.drainJudgmentLogs();
        assertThat(logs).singleElement().satisfies(log -> assertThat(log.strategy()).isNull());
    }

    private static TurnJudgment struggle() {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        put(items, JudgmentItem.END_INTENT, "continue");
        put(items, JudgmentItem.REQUESTED_MODE, "none");
        put(items, JudgmentItem.EMOTION_EXPRESSION, "none");
        put(items, JudgmentItem.EVENT_INFO, "specific");
        put(items, JudgmentItem.REACTION, "struggle_to_answer");
        put(items, JudgmentItem.EXPERIENCING_DEPTH, "undeterminable");
        return new TurnJudgment("jev-test", "v2", items, 0, 0);
    }
}
