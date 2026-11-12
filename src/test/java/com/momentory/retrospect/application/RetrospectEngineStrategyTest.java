package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import com.momentory.retrospect.domain.assistant.StrategyReply;
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
 * 전략 선택을 켠 계정의 일기 작성 채팅 — 판단 모델이 신호를 읽고, 규칙이 굵은 방침을 정하고, 문장 생성 모델이 그 안에서
 * 말을 쓴다. 옛 고정 순서 · 5턴 상한 · 단답 체크인은 거치지 않는다.
 */
class RetrospectEngineStrategyTest {

    private FakeAssistant fake;
    private RetrospectEngine engine;
    private RetrospectState state;
    private final Deque<Optional<TurnJudgment>> judgments = new ArrayDeque<>();
    /** 문장 생성 모델이 이번 턴에 돌려줄 것. null 이면 실패. */
    private StrategyReply reply;
    private final List<TurnStrategy> courses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fake = new FakeAssistant();
        reply = wrote("팀장님께 또 지적을 받으셨군요.", "그 말을 들었을 때 어떠셨어요?");
        engine = engine(12);
        state = new RetrospectState("s1");
        engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        state.enableTurnStrategy();
    }

    private RetrospectEngine engine(int maxTurns) {
        TurnJudge judge = (s, text) -> judgments.isEmpty() ? Optional.empty() : judgments.poll();
        LlmUsageLogger usage = new LlmUsageLogger(0.10, 0.40);
        StrategyDiaryChat chat = new StrategyDiaryChat(judge, new StrategyPolicy(0.5, 0.8, 2, 8),
                (s, text, course) -> {
                    courses.add(course);
                    return Optional.ofNullable(reply);
                }, usage, maxTurns);
        return new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake, judge, chat, usage, e -> { }, 1, 3);
    }

    private static StrategyReply wrote(String ack, String question) {
        return new StrategyReply(ack, question, false, false, "오늘의 일", "none", List.of());
    }

    private static StrategyReply nothingMore(String ack) {
        return new StrategyReply(ack, "", false, true, "오늘의 일", "none", List.of());
    }

    private static StrategyReply newStory(String ack, String question, String topic) {
        return new StrategyReply(ack, question, true, false, topic, "none", List.of());
    }

    /** 방침에 쓰이는 신호만 바꿔 쓴다. 감정 · 사건 · 깊이는 측정값이라 무엇이든 방침이 같다. */
    private static TurnJudgment judged(String end, String requested, String reaction) {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        Map<JudgmentItem, String> choices = Map.of(
                JudgmentItem.END_INTENT, end, JudgmentItem.REQUESTED_MODE, requested,
                JudgmentItem.EMOTION_EXPRESSION, "clear", JudgmentItem.EVENT_INFO, "specific",
                JudgmentItem.REACTION, reaction, JudgmentItem.EXPERIENCING_DEPTH, "stage_3");
        choices.forEach((item, choice) -> items.put(item, new ItemJudgment(choice, 0.9, Map.of(choice, 0.9))));
        return new TurnJudgment("jev-test", "v3", items, 0, 0);
    }

    private static TurnJudgment plain() {
        return judged("continue", "none", "agree_or_elaborate");
    }

    private ReplyDto say(String text, TurnJudgment judgment) {
        judgments.add(Optional.ofNullable(judgment));
        return engine.handle(state, TurnCommand.text(text));
    }

    private TurnJudgmentLog lastLog() {
        return state.drainJudgmentLogs().getLast();
    }

    @Test
    void sendsTheCourseToTheWriterAndUsesWhatItWrote() {
        ReplyDto reply = say("오늘 팀장한테 또 한 소리 들었어.", plain());

        assertThat(reply.text()).isEqualTo("팀장님께 또 지적을 받으셨군요.\n그 말을 들었을 때 어떠셨어요?");
        // 규칙은 「인정 뒤 질문 하나」까지만 정한다. 무엇을 물을지는 문장 생성 모델이 정했다.
        assertThat(courses).containsExactly(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false));
        TurnJudgmentLog log = lastLog();
        assertThat(log.policyVersion()).isEqualTo(StrategyPolicy.VERSION);
        assertThat(log.strategyApplied()).isTrue();
        assertThat(log.generationFailed()).isFalse();
    }

    @Test
    void onlyAcknowledgesForAUserWhoAskedToBeHeardEvenIfTheWriterAsked() {
        reply = wrote("발표가 뜻대로 되지 않아 허무하셨군요.", "어떤 부분이 가장 아쉬우셨나요?");

        ReplyDto said = say("그냥 들어줘. 발표 망쳐서 허무해.", judged("continue", "listen", "agree_or_elaborate"));

        assertThat(said.text()).isEqualTo("발표가 뜻대로 되지 않아 허무하셨군요.");
        assertThat(courses.getLast().focus()).isEqualTo(QuestionFocus.NONE);
    }

    @Test
    void dropsTheNameTheWriterPutInFrontOfTheReplyAndTheQuestion() {
        reply = wrote("정민님, 오늘 시험을 치르셨군요.", "정민님, 시험을 치르면서 어떤 기분이 드셨어요?");

        ReplyDto said = say("오늘 시험 쳤는데 망한 거 같아", plain());

        assertThat(said.text()).isEqualTo("오늘 시험을 치르셨군요.\n시험을 치르면서 어떤 기분이 드셨어요?");
    }

    @Test
    void yieldsWhenTheUserQuestionsWhatBabaSaidAndTellsTheWriterWhy() {
        say("약간 한심하고 실망했어", plain());
        reply = wrote("제가 말을 지나치게 했어요. 약간 한심하고 실망스러우셨던 거죠.", "");

        ReplyDto said = say("뭐?", judged("continue", "none", "puzzled_or_objecting"));

        TurnStrategy course = courses.getLast();
        assertThat(course.type()).isEqualTo(StrategyType.YIELD);
        assertThat(course.reaction()).isEqualTo("puzzled_or_objecting");
        // 물러선 턴에 문장 생성 모델이 묻지 않았다 — 첫 이야기라 다른 기억을 묻는다.
        assertThat(said.text()).isEqualTo(
                "제가 말을 지나치게 했어요. 약간 한심하고 실망스러우셨던 거죠.\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
    }

    @Test
    void asksForAnotherMemoryWhenTheWriterHasNothingMoreToAsk() {
        say("시험 망쳐서 분했어.", plain());
        reply = nothingMore("열심히 했는데 잘 풀지 못해 약간 실망하셨군요.");

        ReplyDto said = say("약간 실망했어. 열심히 공부했는데 잘 풀지 못했거든", plain());

        // 이유까지 들었다 — "이유가 따로 있었나요" 를 캐묻지 않고 다른 기억으로 넘어간다.
        assertThat(said.text()).isEqualTo("열심히 했는데 잘 풀지 못해 약간 실망하셨군요.\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy().focus()).isEqualTo(QuestionFocus.OTHER_TOPIC);
        assertThat(log.strategyApplied()).isTrue();
    }

    @Test
    void proposesWrapUpInsteadOfAskingForAnotherMemoryASecondTime() {
        say("시험 망쳐서 분했어.", plain());
        reply = nothingMore("약간 실망하셨군요.");
        say("약간 실망했어.", plain());
        reply = nothingMore("제 질문이 갑작스러웠나 봐요.");

        // 다른 기억을 물었는데 되물었다 — 같은 문장을 다시 내지 않고 마무리를 제안한다.
        ReplyDto said = say("뭐?", judged("continue", "none", "puzzled_or_objecting"));

        assertThat(said.text()).doesNotContain(RetrospectEngine.OTHER_MEMORY_QUESTION);
        assertThat(lastLog().strategy().focus()).isEqualTo(QuestionFocus.WRAP_UP);
    }

    @Test
    void followsAStoryTheUserBroughtUpAfterBeingInvitedToGoOn() {
        fake.events.add(new ExtractedEvent(1, "시험", "시험을 망친 일", List.of(1, 2)));
        say("시험 망쳐서 분했어.", plain());
        reply = newStory("엄마와 통화를 하셨군요.", "어떤 이야기를 나누셨어요?", "엄마와의 통화");
        say("오늘 엄마랑 통화했어", plain());
        assertThat(state.strategySignals().topicNumber()).isEqualTo(2);

        // 둘째 이야기에서 더 물을 것이 없으면 여기까지 정리할지 묻는다.
        reply = nothingMore("이야기하고 마음이 안정되셨군요.");
        ReplyDto proposal = say("엄마랑 얘기하고 마음이 안정됐어.", plain());
        assertThat(proposal.text()).isEqualTo("이야기하고 마음이 안정되셨군요.\n여기까지 일기로 정리해볼까요?");
        assertThat(proposal.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("이제 정리할래요", "조금 더 이야기할래요");

        // 더 이야기하겠다고 하면 무엇을 말할지 사용자에게 맡긴다.
        ReplyDto more = engine.handle(state, TurnCommand.option("2"));
        assertThat(more.text()).isEqualTo(RetrospectEngine.KEEP_TALKING_INVITATION);

        // 그 뒤에 다른 얘기를 꺼내는 것은 화제 전환(저항)이 아니다 — 물러서지 않고, 질문도 빼지 않는다.
        reply = wrote("퇴근길에 비가 왔군요.", "비 맞으면서 어떠셨어요?");
        ReplyDto followed = say("퇴근길에 비가 왔어", judged("continue", "none", "no_answer_or_topic_change"));
        assertThat(courses.getLast().type()).isEqualTo(StrategyType.EXPLORE);
        assertThat(followed.text()).endsWith("비 맞으면서 어떠셨어요?");
        assertThat(state.strategySignals().resistanceCount()).isZero();
    }

    @Test
    void wrapsUpIntoTheDiaryWhenTheUserAcceptsTheProposal() {
        fake.events.add(new ExtractedEvent(1, "시험", "시험을 망친 일", List.of(1, 2)));
        say("시험 망쳐서 분했어.", plain());
        reply = newStory("엄마와 통화를 하셨군요.", "어떤 이야기를 나누셨어요?", "엄마와의 통화");
        say("오늘 엄마랑 통화했어", plain());
        reply = nothingMore("이야기하고 마음이 안정되셨군요.");
        say("엄마랑 얘기하고 마음이 안정됐어.", plain());

        ReplyDto done = engine.handle(state, TurnCommand.option("1"));

        // 일기를 만들고 감정 탐색으로 넘어간다 — 그 뒤는 이 대화의 범위가 아니다.
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
        assertThat(done.text()).contains(RetrospectEngine.EXPLORATION_TRANSITION);
    }

    @Test
    void doesNotStopAtFiveTurnsOrShowTheOldCheckIn() {
        // 옛 엔진이라면 5턴에서 끝났고, 3턴 뒤의 짧은 답에는 「지금까지 이야기해줘서 고마워요」 정리 선택지가 떴다.
        for (int i = 1; i <= 7; i++) {
            ReplyDto said = say("그냥", plain());
            assertThat(said.text()).as("턴 %d", i).doesNotContain("지금까지 이야기해줘서 고마워요");
            assertThat(said.options()).as("턴 %d", i).isNullOrEmpty();
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
        say("이야기 1", plain());
        say("이야기 2", plain());
        state.drainJudgmentLogs();

        say("이야기 3", plain());

        assertThat(state.phase()).isNotEqualTo(Phase.DIARY_CHAT);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy()).isNotNull();
        assertThat(log.strategyApplied()).isFalse();
    }

    @Test
    void closesRightAwayOnAConfidentEndRequest() {
        ReplyDto said = say("오늘은 이쯤에서 마무리하고 싶어", judged("end_request", "none", "agree_or_elaborate"));

        assertThat(state.phase()).isEqualTo(Phase.COMPLETE);
        assertThat(said.diary()).isNotNull();
        // 마무리는 문장을 만들 필요가 없다 — 문장 생성 모델을 부르지 않는다.
        assertThat(courses).isEmpty();
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategy().type()).isEqualTo(StrategyType.CLOSE);
        assertThat(log.strategyApplied()).isTrue();
    }

    @Test
    void wrapsUpWhenTheUserHasNoOtherMemory() {
        fake.events.add(new ExtractedEvent(1, "발표", "발표를 망친 일", List.of(1, 2)));
        reply = nothingMore("발표가 뜻대로 되지 않아 허무하셨군요.");
        say("발표 망쳐서 허무해. 준비를 덜 해서 그런 것 같아.", plain());
        int written = courses.size();

        engine.handle(state, TurnCommand.text("없어요"));

        // 「다른 기억」 질문에 없다고 하면 지금까지의 이야기로 정리한다 — 판단도 문장 생성도 부르지 않는다.
        assertThat(courses).hasSize(written);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
    }

    @Test
    void stillAcknowledgesAndAsksWhenTheJudgmentFailed() {
        ReplyDto said = say("오늘 팀장한테 또 한 소리 들었어.", null);

        assertThat(said.text()).isEqualTo("팀장님께 또 지적을 받으셨군요.\n그 말을 들었을 때 어떠셨어요?");
        assertThat(courses).containsExactly(new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false));
        TurnJudgmentLog log = lastLog();
        assertThat(log.failed()).isTrue();
        assertThat(log.strategy()).isNotNull();
        assertThat(log.strategyApplied()).isTrue();
    }

    @Test
    void aClearStopPhraseStillClosesWhenTheJudgmentFailed() {
        say("그만할래", null);

        assertThat(state.phase()).isEqualTo(Phase.COMPLETE);
        assertThat(courses).isEmpty();
    }

    @Test
    void usesAPlainAcknowledgementAndAnOpenQuestionWhenTheWriterFailed() {
        reply = null;

        ReplyDto said = say("발표 망쳐서 허무해.", plain());

        // 방침은 그대로 따르되 문장은 고정 문구다 — 기록에서 구분된다.
        assertThat(said.text()).isEqualTo("네, 듣고 있어요.\n조금 더 들려주실래요?");
        TurnJudgmentLog log = lastLog();
        assertThat(log.strategyApplied()).isTrue();
        assertThat(log.generationFailed()).isTrue();
    }

    @Test
    void stillStopsForACrisisTheWriterFlagged() {
        reply = new StrategyReply("많이 지치셨군요.", "", false, false, "요즘", "risk", List.of("hopeless"));

        ReplyDto said = say("요즘 다 내려놓고 싶다는 생각이 들어", plain());

        assertThat(state.phase()).isEqualTo(Phase.SAFETY_HOLD);
        assertThat(said.safetyLevel()).isEqualTo("risk");
    }

    @Test
    void keepsSessionSignalsAcrossSavedTurns() {
        say("발표 망쳐서 허무해.", plain());

        RetrospectState restored = RetrospectState.fromSnapshot(state.toSnapshot());

        assertThat(restored.strategySignals()).isEqualTo(state.strategySignals());
        assertThat(restored.strategySignals().questionsAsked()).isEqualTo(1);
        assertThat(restored.strategySignals().lastType()).isEqualTo(StrategyType.EXPLORE);
        // 켜짐 여부는 요청마다 서비스가 정한다 — 저장되지 않는다.
        assertThat(restored.turnStrategyEnabled()).isFalse();
    }

    @Test
    void judgingAloneKeepsTheOldFlowAndPicksNoCourse() {
        RetrospectState judgedOnly = new RetrospectState("s3");
        engine.start(judgedOnly, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        judgedOnly.enableTurnJudging();
        judgments.add(Optional.of(plain()));

        engine.handle(judgedOnly, TurnCommand.text("발표 망쳐서 허무해."));

        assertThat(courses).isEmpty();
        assertThat(fake.diaryTurnCalls).isEqualTo(1);
        List<TurnJudgmentLog> logs = judgedOnly.drainJudgmentLogs();
        assertThat(logs).singleElement().satisfies(log -> assertThat(log.strategy()).isNull());
    }
}
