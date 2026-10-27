package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

/**
 * 회고 대화 엔진 v2 검증 — AI 는 전부 {@link FakeAssistant}.
 *
 * <p>일기 작성(슬롯 채우기 → 종료) → 분기점 → 감정 탐색(3턴) → 산출물이 정확한 phase·옵션·카드로
 * 이어지는지 본다.
 */
class RetrospectEngineTest {

    private FakeAssistant fake;
    private RetrospectEngine engine;
    private RetrospectState state;

    @BeforeEach
    void setUp() {
        fake = new FakeAssistant();
        LlmUsageLogger usage = new LlmUsageLogger(0.10, 0.40);
        engine = new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake, usage, e -> { },
                1, 3);
        state = new RetrospectState("s1");
    }

    private ReplyDto start() {
        return engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
    }

    /**
     * 슬롯을 채운 채 6턴(최대)까지 이어가 일기를 마친다(조기 종료 금지 규칙). 사건이 하나면 곧바로
     * 감정 확인이, 둘이면 사건 고르기가 돌아온다(분기 없음 · 편지 PRD 14-1).
     */
    private ReplyDto toBranch() {
        start();
        if (fake.events.isEmpty()) {
            fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        }
        fake.turnEvent = "면접 스터디에서 팀원이 말을 끊었다";
        fake.turnMeaning = "내 의견이 가볍게 다뤄진 게 계속 걸린다";
        fake.turnEmotionPresent = true;
        ReplyDto reply = null;
        for (int i = 0; i < RetrospectState.DIARY_MAX_TURNS; i++) {
            reply = engine.handle(state, TurnCommand.text("팀원이 자꾸 말을 끊어서 속상했어요."));
        }
        return reply;
    }

    // ── 시작 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시작 — 분기 없이 일정 소재로 일기 작성 질문을 낸다(감정 선택 없음).")
    void startOpensDiaryChat() {
        ReplyDto reply = start();

        assertThat(reply.phase()).isEqualTo("diary_chat");
        assertThat(reply.text()).isEqualTo("정민님, 오늘 면접 스터디, 어땠어요?");
        assertThat(reply.options()).isNull();
    }

    @Test
    @DisplayName("일정이 없으면 '오늘 하루' 질문으로 연다.")
    void startWithoutSchedule() {
        ReplyDto reply = engine.start(state, StartCommand.today("지은", "취업"));

        assertThat(reply.phase()).isEqualTo("diary_chat");
        assertThat(reply.text()).contains("오늘 하루 중에서 가장 기억에 남는 일");
    }

    // ── 일기 작성 → 종료 ────────────────────────────────────────────────

    @Test
    @DisplayName("슬롯이 1턴에 다 모여도 6턴까지 이어가고, 6턴에서 분기 없이 감정 확인으로 넘어간다(조기 종료 금지).")
    void diaryRunsToSixTurnsEvenWhenSlotsFillEarly() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        fake.turnEvent = "면접 스터디에서 팀원이 말을 끊었다";
        fake.turnMeaning = "내 의견이 가볍게 다뤄진 게 계속 걸린다";
        fake.turnEmotionPresent = true; // 1턴에 사건·감정·의미가 다 찬다

        // 슬롯이 다 찼어도 6턴 전에는 종료하지 않고 계속 일기 작성 채팅을 이어간다.
        for (int i = 1; i < RetrospectState.DIARY_MAX_TURNS; i++) {
            ReplyDto mid = engine.handle(state, TurnCommand.text("속상한 일이 있었어요 " + i));
            assertThat(mid.phase()).as("%d턴", i).isEqualTo("diary_chat");
        }

        // 6턴에 도달하면 일기를 정리하고 곧바로 감정 확인을 묻는다.
        ReplyDto reply = engine.handle(state, TurnCommand.text("계속 마음에 걸려요."));
        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        assertThat(reply.text()).startsWith(RetrospectEngine.EXPLORATION_TRANSITION)
                .contains("팀원이 말을 끊었던 일을 떠올렸을 때");
        assertThat(reply.options()).extracting("label")
                .doesNotContain("감정을 더 알아볼래요", "일기 확인하러 갈래요");
        assertThat(fake.extractCalls).isEqualTo(1);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("슬롯이 안 차도 6턴에 도달하면 있는 것만으로 종료한다.")
    void diaryStopsAtSixTurns() {
        start();
        fake.failDiaryChat = true; // 추출 없음 → 슬롯이 채워지지 않는다

        for (int i = 1; i <= 5; i++) {
            ReplyDto mid = engine.handle(state, TurnCommand.text("오늘 여러 일이 있었어요 " + i));
            assertThat(mid.phase()).as("%d번째 턴", i).isEqualTo("diary_chat");
        }
        ReplyDto sixth = engine.handle(state, TurnCommand.text("마지막으로 이런 일도 있었어요."));
        // 뽑힌 사건이 없으면 감정 탐색 없이 일기로 마무리한다.
        assertThat(sixth.phase()).isEqualTo("complete");
        assertThat(sixth.wishCard()).isNull();
    }

    @Test
    @DisplayName("사용자가 그만하려 하면 감정 탐색 없이 즉시 일기로 마무리한다(편지 PRD 14-1).")
    void stopRequestEndsDiaryChat() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        ReplyDto reply = engine.handle(state, TurnCommand.text("이제 그만할래"));

        assertThat(reply.phase()).isEqualTo("complete");
        assertThat(reply.wishCard()).isNull();
        assertThat(state.explorationEntered()).isFalse();
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    // ── 사건 고르기 · 옛 분기점 ─────────────────────────────────────────

    @Test
    @DisplayName("사건이 둘이면 어느 이야기를 더 나눌지 고른다 — 「일기 보러 갈래요」로 빠지는 선지는 없다")
    void twoEventsAskWhichToExploreWithoutExit() {
        fake.events.add(new ExtractedEvent(1, "면접", "면접에서 답변이 막혔던 일", List.of(1, 2)));
        fake.events.add(new ExtractedEvent(2, "저녁", "친구와 저녁을 먹었던 일", List.of(3)));

        ReplyDto reply = toBranch();

        assertThat(reply.phase()).isEqualTo("await_branch");
        assertThat(reply.text()).startsWith(RetrospectEngine.EXPLORATION_TRANSITION);
        assertThat(reply.options()).extracting("label")
                .containsExactly("면접에서 답변이 막혔던 일", "친구와 저녁을 먹었던 일");
    }

    @Test
    @DisplayName("배포 전에 분기에서 멈춘 세션은 「일기 확인하러 갈래요」를 옛 방식대로 받아 카드 없이 마무리한다")
    void legacyBranchViewStillCompletes() {
        start();
        state.diaryDraft("오늘의 일기.");
        state.changePhase(Phase.AWAIT_BRANCH);
        state.lastOptions(List.of(com.momentory.retrospect.domain.Choice.of(RetrospectEngine.BRANCH_EXPLORE),
                com.momentory.retrospect.domain.Choice.of(RetrospectEngine.BRANCH_VIEW)));

        ReplyDto reply = engine.handle(state, TurnCommand.option("2"));

        assertThat(reply.phase()).isEqualTo("complete");
        assertThat(reply.wishCard()).isNull();
    }

    @Test
    @DisplayName("배포 전에 분기에서 멈춘 세션의 「감정을 더 알아볼래요」는 사건 고르기로 이어진다")
    void legacyBranchExploreStillOpensEventSelection() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        state.events(List.copyOf(fake.events));
        state.diaryDraft("오늘의 일기.");
        state.changePhase(Phase.AWAIT_BRANCH);
        state.lastOptions(List.of(com.momentory.retrospect.domain.Choice.of(RetrospectEngine.BRANCH_EXPLORE),
                com.momentory.retrospect.domain.Choice.of(RetrospectEngine.BRANCH_VIEW)));

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.phase()).isEqualTo("await_branch");
        assertThat(reply.options()).extracting("label").containsExactly("팀원이 말을 끊었던 일");
    }

    @Test
    @DisplayName("위기 안내가 나왔던 날은 「이어서 얘기하기」로 대화를 마쳐도 감정 탐색 없이 카드 없이 끝난다")
    void crisisGuidanceDayEndsWithoutExplorationOrCard() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        engine.handle(state, TurnCommand.text("발표 준비를 했어요."));
        assertThat(engine.handle(state, TurnCommand.text("죽고 싶어요.")).phase()).isEqualTo("safety_hold");
        engine.handle(state, TurnCommand.text("")); // 이어서 얘기하기

        ReplyDto done = engine.handle(state, TurnCommand.text("이제 그만할래"));
        assertThat(done.wishCard()).isNull();

        // 6턴을 다 채워 마친 경우도 같다.
        state = new RetrospectState("s-crisis");
        start();
        fake.turnEvent = "면접 스터디에서 팀원이 말을 끊었다";
        engine.handle(state, TurnCommand.text("죽고 싶어요."));
        engine.handle(state, TurnCommand.text(""));
        ReplyDto last = null;
        for (int i = 0; i < RetrospectState.DIARY_MAX_TURNS && "diary_chat".equals(state.phase().key()); i++) {
            last = engine.handle(state, TurnCommand.text("팀원이 자꾸 말을 끊어서 속상했어요."));
        }
        assertThat(last.phase()).isEqualTo("complete");
        assertThat(last.wishCard()).isNull();
        assertThat(last.text()).doesNotContain("편지로 골라 올게요");
        assertThat(state.explorationEntered()).isFalse();
    }

    @Test
    @DisplayName("배포 전 세션의 단답 체크인 「일기 보러 갈래요」는 옛 방식대로 카드 없이 마무리한다")
    void legacyCheckinGoDiaryStillCompletes() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        state.lastOptions(List.of(com.momentory.retrospect.domain.Choice.of(RetrospectEngine.GO_DIARY),
                com.momentory.retrospect.domain.Choice.of(RetrospectEngine.KEEP_TALKING)));

        ReplyDto done = engine.handle(state, TurnCommand.option("1"));

        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNull();
    }

    // ── 감정 탐색 2턴 → 바람 카드 ───────────────────────────────────────

    @Test
    @DisplayName("감정 · 바람을 고르면 바로 완료한다 — 작은 행동은 묻지 않고 카드의 그 칸은 비운다(편지에서 고른다)")
    void explorationEndsAtNeedAndLeavesSmallActionEmpty() {
        fake.emotions.add(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        ReplyDto emotionTurn = toBranch();
        assertThat(emotionTurn.phase()).isEqualTo("emotion_exploration");

        ReplyDto needTurn = engine.handle(state, TurnCommand.option("1"));
        assertThat(needTurn.phase()).isEqualTo("emotion_exploration");
        ReplyDto done = engine.handle(state, TurnCommand.option("1"));

        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.text()).contains("내일 아침엔 이 마음에 맞는 작은 일을 편지로 골라 올게요");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isNull();
        assertThat(done.wishCard().situation()).isEqualTo("팀원이 말을 끊었던 일");
        assertThat(done.wishCard().emotions()).containsExactly("angry");
        assertThat(state.explorationTurn()).isEqualTo(2);
    }

    @Test
    @DisplayName("배포 전에 작은 행동 답을 기다리던 세션은 무엇을 보내든 카드를 만들어 마무리한다")
    void legacySessionWaitingForSmallActionCompletes() {
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 감정
        state.bumpExplorationTurn(); // 옛 흐름의 3턴째(작은 행동)에 멈춰 있던 상태

        ReplyDto done = engine.handle(state, TurnCommand.text("산책하기"));

        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isNull();
    }

    @Test
    @DisplayName("단답 체크인에서 「이제 정리할래요」를 고르면 끝나지 않고 감정 · 바람으로 넘어간다")
    void checkinWrapUpMovesToExploration() {
        start();
        fake.events.add(new ExtractedEvent(1, "발표", "발표에서 말이 막혔던 일", List.of(1)));
        fake.turnEvent = "발표에서 말이 막혔다";
        fake.turnOffTopic = true;
        for (int i = 0; i < 5 && state.lastOptions().isEmpty(); i++) {
            engine.handle(state, TurnCommand.text("딱히"));
        }
        assertThat(state.lastOptions()).extracting("label")
                .containsExactly("이제 정리할래요", "조금 더 이야기할래요");

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        assertThat(reply.text()).startsWith(RetrospectEngine.EXPLORATION_TRANSITION);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 더 물어볼 게 없다고 해도 6턴 전에 자동 종료하지 않는다")
    void noMoreToAskDoesNotEndEarly() {
        RetrospectState state = new RetrospectState("s-1");
        engine.start(state, StartCommand.single("팀 발표", null, "정민"));
        fake.turnEvent = "발표에서 말이 막혔다";
        fake.turnMeaning = "계속 곱씹게 된다";
        fake.turnEmotionPresent = true;

        // 최소 턴(4) 전에는 신호가 있어도 이어간다.
        fake.turnNoMoreToAsk = true;
        for (int i = 0; i < RetrospectState.DIARY_MIN_TURNS - 1; i++) {
            ReplyDto mid = engine.handle(state, new TurnCommand("네 그랬어요", null));
            assertThat(mid.phase()).as("%d턴", i + 1).isEqualTo(Phase.DIARY_CHAT.key());
        }

        // 모델의 종료 신호만으로 조기 종료하지 않는다.
        ReplyDto done = engine.handle(state, new TurnCommand("네 그랬어요", null));

        assertThat(done.phase()).isEqualTo(Phase.DIARY_CHAT.key());
        assertThat(state.diaryTurn()).isEqualTo(RetrospectState.DIARY_MIN_TURNS);
        assertThat(state.diaryTurn()).isLessThan(RetrospectState.DIARY_MAX_TURNS);
    }

    @Test
    @DisplayName("신호가 없으면 예전대로 최대 턴까지 이어간다")
    void keepsGoingWithoutSignal() {
        RetrospectState state = new RetrospectState("s-2");
        engine.start(state, StartCommand.single("팀 발표", null, "정민"));
        fake.turnEvent = "발표에서 말이 막혔다";
        fake.turnNoMoreToAsk = false;

        for (int i = 0; i < RetrospectState.DIARY_MAX_TURNS - 1; i++) {
            assertThat(engine.handle(state, new TurnCommand("조금 더 얘기할게요", null)).phase())
                    .isEqualTo(Phase.DIARY_CHAT.key());
        }
        ReplyDto done = engine.handle(state, new TurnCommand("조금 더 얘기할게요", null));

        // 뽑힌 사건이 없어 감정 탐색 없이 마무리된다 — 여기서 보는 것은 6턴까지 이어갔다는 점이다.
        assertThat(done.phase()).isEqualTo(Phase.COMPLETE.key());
        assertThat(state.diaryTurn()).isEqualTo(RetrospectState.DIARY_MAX_TURNS);
    }

    @Test
    @DisplayName("사건이 상한만큼 나왔으면 체크인 재개 질문이 새 소재를 열지 않는다")
    void checkinResumeDoesNotOpenNewTopic() {
        RetrospectState state = new RetrospectState("s-3");
        engine.start(state, StartCommand.single("팀 발표", null, "정민"));
        // 슬롯을 다 채우고 사건을 상한까지 만든다(핵심 + 곁가지).
        fake.turnEvent = "발표에서 말이 막혔다";
        fake.turnMeaning = "계속 곱씹게 된다";
        fake.turnEmotionPresent = true;
        engine.handle(state, new TurnCommand("발표가 있었어요", null));
        state.addSecondaryEvents(java.util.List.of("친구와 다툼"));
        assertThat(state.knownEventCount()).isEqualTo(RetrospectState.MAX_EVENTS);

        // 단답으로 체크인을 띄운 뒤 「조금 더」를 고른다.
        fake.turnOffTopic = true;
        for (int i = 0; i < 3 && state.lastOptions().isEmpty(); i++) {
            engine.handle(state, new TurnCommand("딱히", null));
        }
        assertThat(state.lastOptions()).as("체크인이 떠야 한다").isNotEmpty();
        ReplyDto resumed = engine.handle(state, new TurnCommand(null, java.util.List.of("2")));

        assertThat(resumed.text()).contains("지금까지 이야기한 것 중에");
        assertThat(resumed.text()).doesNotContain("조금 더 이야기해 주고 싶은 게 있을까요");
    }

    @Test
    @DisplayName("사건이 하나뿐이면 예전대로 열린 질문을 낸다")
    void openQuestionWhenRoomForAnotherEvent() {
        RetrospectState state = new RetrospectState("s-4");
        engine.start(state, StartCommand.single("팀 발표", null, "정민"));
        fake.turnEvent = "발표에서 말이 막혔다";
        fake.turnMeaning = "계속 곱씹게 된다";
        fake.turnEmotionPresent = true;
        engine.handle(state, new TurnCommand("발표가 있었어요", null));
        assertThat(state.knownEventCount()).isEqualTo(1);

        fake.turnOffTopic = true;
        for (int i = 0; i < 3 && state.lastOptions().isEmpty(); i++) {
            engine.handle(state, new TurnCommand("딱히", null));
        }
        ReplyDto resumed = engine.handle(state, new TurnCommand(null, java.util.List.of("2")));

        assertThat(resumed.text()).contains("다른 기억에 남는 일");
    }

    @Test
    @DisplayName("감정 확인 질문이 어떤 사건인지 이름을 괄호 없이 밝힌다 — 조사는 이름의 마지막 글자로 고른다")
    void emotionConfirmNamesTheEvent() {
        // 근거가 더 많은 '개발'이 대표 사건이 된다 — 마지막에 언급된 '친구와 다툼'이 아니라.
        fake.events.add(new ExtractedEvent(1, "개발", "카페에서 개발함", List.of(1, 2, 3)));
        fake.events.add(new ExtractedEvent(2, "친구와 다툼", "저녁에 다툼", List.of(4)));
        fake.emotions.add(new ExtractedEmotion(1, "재밌었어", Emotion.HAPPY, 2,
                EmotionPhase.DURING, "재밌었어", List.of(1)));
        assertThat(toBranch().phase()).isEqualTo(Phase.AWAIT_BRANCH.key());

        ReplyDto confirm = engine.handle(state, TurnCommand.option("1"));

        assertThat(confirm.text()).startsWith("카페에서 개발함을 떠올렸을 때");
        assertThat(confirm.text()).doesNotContain("개발를").doesNotContain("「");
        assertThat(confirm.text()).doesNotContain("친구와 다툼");
    }

    @Test
    @DisplayName("카드 상황은 사건 슬롯이 아니라 감정을 물은 대표 사건이다")
    void cardSituationIsTheSelectedEvent() {
        fake.events.add(new ExtractedEvent(1, "개발", "카페에서 개발함", List.of(1, 2, 3)));
        fake.emotions.add(new ExtractedEmotion(1, "재밌었어", Emotion.HAPPY, 2,
                EmotionPhase.DURING, "재밌었어", List.of(1)));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 감정

        ReplyDto done = engine.handle(state, TurnCommand.option("1")); // 바람 → 완료

        assertThat(done.wishCard().situation()).isEqualTo("카페에서 개발함");
    }

    @Test
    void typedFixedNeedIsRecognizedForCatalogSelection() {
        fake.emotions.add(new ExtractedEmotion(1, "피곤해", Emotion.TIRED, null, null, "피곤해요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 감정

        engine.handle(state, TurnCommand.text(" 휴식 "));

        assertThat(state.needs()).extracting(com.momentory.retrospect.domain.Need::word)
                .containsExactly("휴식");
        assertThat(state.desiredState()).isEqualTo("휴식");
    }

    // ── 위기 안내 → 이어서 얘기하기 ─────────────────────────────────────

    @Test
    @DisplayName("위기 안내 후 '이어서 얘기하기' → 위기 발화를 답으로 삼지 않고 직전 질문을 다시 낸다")
    void safetyResumeReshowsLastQuestion() {
        start();
        fake.turnQuestion = "그때 마음이 어땠어요?";
        ReplyDto question = engine.handle(state, TurnCommand.text("발표 준비를 했어요."));
        assertThat(question.phase()).isEqualTo(Phase.DIARY_CHAT.key());

        ReplyDto hold = engine.handle(state, TurnCommand.text("죽고 싶어요."));
        assertThat(hold.phase()).isEqualTo(Phase.SAFETY_HOLD.key());

        int diaryTurnAtHold = state.diaryTurn();
        ReplyDto resumed = engine.handle(state, TurnCommand.text(""));

        assertThat(resumed.phase()).isEqualTo(Phase.DIARY_CHAT.key());
        assertThat(resumed.text()).isEqualTo(question.text());
        // 이어가기는 답변 턴이 아니다 — 일기 턴을 소모하지 않고 질문만 다시 보여준다.
        assertThat(state.diaryTurn()).isEqualTo(diaryTurnAtHold);
    }

    @Test
    void routineSchedulesAreHintsAndFallbackDoesNotInventThem() {
        var schedules = List.of(
                new com.momentory.retrospect.domain.ScheduleItem(10L, "운동", Emotion.HAPPY, true),
                new com.momentory.retrospect.domain.ScheduleItem(11L, "가스비 점검", null, true),
                new com.momentory.retrospect.domain.ScheduleItem(12L, "공부", null, true),
                new com.momentory.retrospect.domain.ScheduleItem(13L, "청소", null, true));
        ReplyDto opening = engine.start(state, new StartCommand(schedules, null, "건강"));
        assertThat(opening.text()).contains("운동", "가스비 점검", "공부", "가장 기억에 남는 일")
                .doesNotContain("청소");
        assertThat(state.scheduleId()).isEqualTo(10L);
        assertThat(state.schedule()).isEqualTo("운동");
        assertThat(state.scheduleHint()).isTrue();
        assertThat(fake.diaryTurnCalls).isZero();
        fake.failDiary = true;
        engine.handle(state, TurnCommand.text("그만할래"));
        assertThat(state.diaryDraft()).isEqualTo("오늘 하루를 돌아봤다.");
    }

    @Test
    void interestsRankOnlyEventfulCandidatesWithoutHomeEmotion() {
        var schedules = List.of(
                new com.momentory.retrospect.domain.ScheduleItem(1L, "운동", Emotion.HAPPY, true),
                new com.momentory.retrospect.domain.ScheduleItem(2L, "친구 약속", Emotion.ANGRY, true),
                new com.momentory.retrospect.domain.ScheduleItem(3L, "인턴 면접", null, false));
        assertThat(engine.start(state, new StartCommand(schedules, null, "취업")).text())
                .isEqualTo("오늘 인턴 면접, 어땠어요?");
        assertThat(state.scheduleId()).isEqualTo(3L);
        var another = new RetrospectState("another");
        assertThat(engine.start(another, new StartCommand(schedules, null, "건강")).text())
                .isEqualTo("오늘 친구 약속, 어땠어요?");
    }

    @Test
    void selectedEventControlsCandidatesPromptsAndCard() {
        fake.events.add(new ExtractedEvent(7, "면접", "면접에서 답변이 막혔던 일", List.of(1, 2, 3)));
        fake.events.add(new ExtractedEvent(9, "저녁", "친구와 저녁을 먹었던 일", List.of(4)));
        fake.emotions.add(new ExtractedEmotion(7, "불안", Emotion.ANXIOUS, 2, null, "불안", List.of(1)));
        fake.emotions.add(new ExtractedEmotion(9, "행복", Emotion.HAPPY, 2, null, "행복", List.of(4)));
        fake.emotions.add(new ExtractedEmotion(null, "화남", Emotion.ANGRY, 2, null, "화남", List.of(5)));
        ReplyDto selection = toBranch();
        assertThat(selection.options()).extracting("label").containsExactly(
                "면접에서 답변이 막혔던 일", "친구와 저녁을 먹었던 일");
        assertThat(state.explorationEntered()).isFalse();
        assertThat(engine.handle(state, TurnCommand.option("99")).phase()).isEqualTo("await_branch");
        ReplyDto emotion = engine.handle(state, TurnCommand.option("2"));
        assertThat(state.selectedEventId()).isEqualTo(9);
        assertThat(state.explorationTurn()).isZero();
        assertThat(emotion.text()).contains("친구와 저녁을 먹었던 일");
        assertThat(emotion.options()).extracting("label").containsExactly("행복함");
        // 사용자가 후보 대신 확인한 감정을 생성 입력에 사용한다.
        engine.handle(state, TurnCommand.text("평온함"));
        var prompts = new com.momentory.retrospect.infrastructure.ai.PromptFactory(2000,
                com.momentory.retrospect.infrastructure.ai.EmotionPromptVariant.ZERO_SHOT);
        assertThat(prompts.needsPrompt(state)).contains("친구와 저녁을 먹었던 일", "평온함")
                .doesNotContain("불안함", "행복함");
        ReplyDto done = engine.handle(state, TurnCommand.option("1"));
        assertThat(done.wishCard().situation()).isEqualTo("친구와 저녁을 먹었던 일");
        assertThat(done.wishCard().emotions()).containsExactly("calm");
    }

    @Test
    void noExtractedEventsCompletesWithoutCard() {
        start();
        ReplyDto done = engine.handle(state, TurnCommand.text("그만할래"));
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNull();
        assertThat(state.explorationEntered()).isFalse();
    }

    @Test
    @DisplayName("배포 전 사건 고르기 목록에 남아 있던 「일기 보러 갈래요」는 옛 방식대로 카드 없이 마무리한다")
    void legacyEventSelectionExitStillCompletes() {
        fake.events.add(new ExtractedEvent(1, "면접", "면접에서 답변이 막혔던 일", List.of(1, 2)));
        fake.events.add(new ExtractedEvent(2, "저녁", "친구와 저녁을 먹었던 일", List.of(3)));
        toBranch();
        state.lastOptions(List.of(com.momentory.retrospect.domain.Choice.of("면접에서 답변이 막혔던 일"),
                com.momentory.retrospect.domain.Choice.of("친구와 저녁을 먹었던 일"),
                com.momentory.retrospect.domain.Choice.of(RetrospectEngine.GO_DIARY)));
        ReplyDto done = engine.handle(state, TurnCommand.option("3"));
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNull();
    }

    @Test
    void selectedEventWithNoEmotionsDoesNotBorrowInferredEmotion() {
        fake.inferredEmotion = Emotion.ANXIOUS;
        ReplyDto emotion = toBranch();
        assertThat(emotion.options()).isEmpty();
        assertThat(state.confirmedEmotions()).isEmpty();
    }

}
