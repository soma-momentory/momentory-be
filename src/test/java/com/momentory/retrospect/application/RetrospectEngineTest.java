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
     * 슬롯을 채운 채 5턴(최대)까지 이어가 일기를 마친다(조기 종료 금지 규칙). 사건이 하나면 곧바로
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

    @ParameterizedTest
    @ValueSource(strings = {"없어", "없어요.", "더는 없어", "다른 일은 없어요", "아니요", "그게 다예요",
            "아니요, 없는 것 같아요", "딱히 없는데요",
            "딱히 없었어", "없었어요.", "특별히 없었어", "다른 일은 없었습니다.", "더는 없었네요",
            "딱히 다른 일은 없었어", "특별히 기억에 남는 일은 없었어요", "응, 딱히 다른 건 없었어."})
    @DisplayName("다른 기억을 물은 뒤 없다고 하면 되묻지 않고 자유 대화를 끝내 감정 탐색으로 간다")
    void noOtherMemoryFinishesDiaryChat(String answer) {
        start();
        fake.turnEvent = "친구를 만났다";
        fake.turnEmotionPresent = true;
        fake.turnEmpathy = "반가운 만남이었군요.";
        fake.events.add(new ExtractedEvent(1, "친구 만남", "친구와 함께한 시간", List.of(1, 2)));
        engine.handle(state, TurnCommand.text("친구를 만났어요."));
        assertThat(engine.handle(state, TurnCommand.text("반가웠어요.")).text())
                .endsWith("오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");

        ReplyDto reply = engine.handle(state, TurnCommand.text(answer));

        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        assertThat(reply.text()).startsWith("좋아요. 지금까지 들려주신 이야기를 잘 담아둘게요.\n"
                + RetrospectEngine.EXPLORATION_TRANSITION);
        assertThat(state.diaryTurn()).isEqualTo(3);
        assertThat(fake.diaryTurnCalls).isEqualTo(2);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"싫다", "싫어", "싫어요.", "다른 이야기는 하기 싫어요", "안 할래요", "말하고 싶지 않아", "없어", "딱히 없었어", "아니요", "그만할래",
            "더 말할 건 없어", "할 말이 없어요", "그만하고 싶어요"})
    @DisplayName("다른 이야기 제안을 거절하면 사건 재선택 없이 대표 사건의 감정 탐색으로 바로 간다")
    void refusesAnotherTopicGoesStraightToEmotion(String answer) {
        start();
        state.event("발표");
        state.addSecondaryEvents(List.of("친구 만남"));
        state.markEmotionSeen();
        state.markTopicCauseDiscussed();
        state.bumpDiaryTurn();
        fake.turnEvent = "발표";
        fake.events.add(new ExtractedEvent(1, "친구 만남", "친구와 밥을 먹은 일", List.of(1)));
        fake.events.add(new ExtractedEvent(2, "발표", "발표 도중 말이 막힌 일", List.of(2, 3)));
        assertThat(engine.handle(state, TurnCommand.text("발표를 준비할 시간이 없었어요.")).text())
                .endsWith("지금까지 이야기한 것 중에, 다른 이야기를 나눠볼까요?");

        ReplyDto reply = engine.handle(state, TurnCommand.text(answer));

        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        assertThat(reply.done()).isFalse();
        assertThat(reply.text()).contains("발표 도중 말이 막힌 일").doesNotContain("어떤 이야기를 조금 더 나눠볼까요?");
        assertThat(state.selectedEventId()).isEqualTo(2);
        assertThat(state.awaitingEventSelection()).isFalse();
        assertThat(fake.diaryTurnCalls).isEqualTo(1);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"싫지 않아", "다른 이야기는 싫어. 대신 산책한 일은 말할게", "싫어요?"})
    void refusalWithNewContentOrDoubleNegativeIsNotAnExit(String answer) {
        start();
        state.addAssistantMessage("지금까지 이야기한 것 중에, 다른 이야기를 나눠볼까요?");
        fake.turnEvent = "산책";

        assertThat(engine.handle(state, TurnCommand.text(answer)).phase()).isEqualTo("diary_chat");
        assertThat(fake.diaryTurnCalls).isEqualTo(1);
        assertThat(fake.diaryWriteCalls).isZero();
    }

    @Test
    void negativeEmotionOutsideTopicInvitationIsNotARefusal() {
        start();
        fake.turnEvent = "회의";
        fake.turnEmotionPresent = true;
        assertThat(engine.handle(state, TurnCommand.text("싫어요")).phase()).isEqualTo("diary_chat");
        assertThat(state.topicCauseDiscussed()).isTrue();
        assertThat(fake.diaryWriteCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"친구가 칭찬해줘서 기뻤어요", "오래 기다리게 해서 짜증났어요"})
    @DisplayName("감정과 이유를 함께 답하면 기분·계기·다른 기억을 묻지 않고 공감만 보낸다")
    void emotionAndCauseTogetherReceiveOnlyEmpathy(String answer) {
        start();
        fake.turnEvent = "친구와 대화";
        fake.turnEmotionPresent = true;
        fake.turnEmotionCausePresent = true;
        fake.turnEmpathy = "친구와의 대화가 마음에 남으셨군요.";
        fake.turnQuestion = "그때 어떤 기분이 드셨나요?";

        ReplyDto reply = engine.handle(state, TurnCommand.text(answer));

        assertThat(reply.text()).isEqualTo(fake.turnEmpathy).doesNotContain("?", "？");
        assertThat(state.topicCauseDiscussed()).isTrue();
        assertThat(fake.diaryWriteCalls).isZero();
        fake.turnEmotionCausePresent = false;
        fake.turnEmotionPresent = false;
        assertThat(engine.handle(state, TurnCommand.text("맞아")).text())
                .endsWith("오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
    }

    @Test
    @DisplayName("기분 질문 뒤 감정, 계기 질문 뒤 짧은 이유를 들어도 체크인이나 질문을 덧붙이지 않는다")
    void shortValidCauseBypassesCheckinAndReceivesOnlyEmpathy() {
        start();
        fake.turnEvent = "외출";
        engine.handle(state, TurnCommand.text("외출했어요"));
        fake.turnEmotionPresent = true;
        engine.handle(state, TurnCommand.text("우울했어요"));
        fake.turnEmotionPresent = false;
        fake.turnEmotionCausePresent = true;
        fake.turnEmpathy = "비가 오는 날이라 마음이 가라앉으셨군요.";

        ReplyDto reply = engine.handle(state, TurnCommand.text("비"));

        assertThat(reply.text()).isEqualTo(fake.turnEmpathy);
        assertThat(reply.options()).isNull();
        assertThat(reply.phase()).isEqualTo("diary_chat");
        assertThat(state.diaryTurn()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"다른 일은 없는데 산책은 했어요.", "없어요?", "잘 모르겠어요",
            "딱히 없었어?", "딱히 없었어. 아, 저녁에 산책했어.", "특별한 일은 없었지만 친구는 만났어.",
            "딱히 없었던 건 아니야"})
    @DisplayName("새 경험을 덧붙이거나 질문·모호한 답이면 없음으로 오인해 종료하지 않는다")
    void otherMemoryAnswerWithContentDoesNotFinish(String answer) {
        start();
        fake.turnEvent = "친구를 만났다";
        fake.turnEmotionPresent = true;
        engine.handle(state, TurnCommand.text("친구를 만났어요."));
        engine.handle(state, TurnCommand.text("반가웠어요."));
        fake.turnEvent = "산책했다";
        fake.turnEmotionPresent = false;

        assertThat(engine.handle(state, TurnCommand.text(answer)).phase()).isEqualTo("diary_chat");
        assertThat(fake.diaryTurnCalls).isEqualTo(3);
        assertThat(fake.diaryWriteCalls).isZero();
    }

    @Test
    @DisplayName("다른 기억 질문 직후가 아닌 없어요는 자유 대화를 조기 종료하지 않는다")
    void noAnswerToDifferentQuestionDoesNotFinish() {
        start();
        assertThat(engine.handle(state, TurnCommand.text("없어요.")).phase()).isEqualTo("diary_chat");
        assertThat(fake.diaryTurnCalls).isEqualTo(1);
        assertThat(fake.diaryWriteCalls).isZero();
    }

    @Test
    @DisplayName("아직 일어나지 않은 일정에는 공감 뒤 그전의 실제 일을 묻고 미래 사건 슬롯을 넣지 않는다")
    void pendingScheduleAsksAboutEarlierExperience() {
        start();
        fake.turnEventPending = true;
        fake.turnEvent = "유나를 만났다"; // 모델이 잘못 채운 미래 사건도 채택하지 않는다.
        fake.turnMeaning = "즐거운 만남이었다";
        fake.turnEmotionPresent = true;
        fake.turnEmpathy = "아직 만나기 전이시군요.";
        fake.turnQuestion = "만나면 어떤 기분일 것 같아요?";

        ReplyDto reply = engine.handle(state, TurnCommand.text("아직 안 만났어."));

        assertThat(reply.text()).isEqualTo("아직 만나기 전이시군요.\n그럼 그전에는 어떤 일을 하셨어요?");
        assertThat(reply.phase()).isEqualTo("diary_chat");
        assertThat(state.event()).isNull();
        assertThat(state.secondaryEvents()).isEmpty();
        assertThat(state.topicEmotionSeen()).isFalse();
        assertThat(state.topicMeaningSeen()).isFalse();
        assertThat(state.diaryTurn()).isEqualTo(1);

        fake.turnEventPending = false;
        fake.turnEvent = "오전에 산책했다";
        fake.turnMeaning = null;
        fake.turnEmotionPresent = false;
        fake.turnEmpathy = "오전에는 산책하며 시간을 보내셨군요.";
        ReplyDto past = engine.handle(state, TurnCommand.text("오전에는 산책했어요."));
        assertThat(past.text()).isEqualTo(fake.turnEmpathy + "\n그때 어떤 기분이 드셨나요?");
        assertThat(state.event()).isEqualTo("오전에 산책했다");
        assertThat(state.topicTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("진행 중 일정의 시간 정정은 체크인으로 막지 않고 이전 경험 슬롯을 보존한다")
    void pendingScheduleAfterThirdTurnPreservesEarlierEvent() {
        start();
        fake.turnEvent = "산책했다";
        engine.handle(state, TurnCommand.text("산책했어요."));
        engine.handle(state, TurnCommand.text("공원을 걸었어요."));
        fake.turnEventPending = true;
        fake.turnEvent = "유나를 만났다";
        fake.turnEmpathy = "약속은 아직 전이시군요.";

        ReplyDto reply = engine.handle(state, TurnCommand.text("아직 안 만났어"));

        assertThat(reply.options()).isNull();
        assertThat(reply.text()).endsWith("그럼 그전에는 어떤 일을 하셨어요?");
        assertThat(state.event()).isEqualTo("산책했다");
        assertThat(state.knownEventCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"아직 안만났어", "아직 안 만났어요.", "아직 못 만났어"})
    @DisplayName("AI 장애 때도 명확한 아직 안 만났다는 답변에는 이전 일을 묻는다")
    void pendingScheduleFallbackKeepsConversationGoing(String content) {
        start();
        fake.failDiaryChat = true;

        assertThat(engine.handle(state, TurnCommand.text(content)).text())
                .contains("들려주신 이야기").endsWith("그럼 그전에는 어떤 일을 하셨어요?");
    }

    @Test
    @DisplayName("아직 전인 일정도 답변 5턴 상한을 늘리지 않는다")
    void pendingScheduleDoesNotBypassFiveTurnLimit() {
        start();
        fake.turnEventPending = true;
        fake.turnEmpathy = "아직 만나기 전이시군요.";
        for (int i = 0; i < 4; i++) {
            assertThat(engine.handle(state, TurnCommand.text("아직 안 만났어요.")).phase())
                    .isEqualTo("diary_chat");
        }

        ReplyDto reply = engine.handle(state, TurnCommand.text("아직 안 만났어요."));

        assertThat(state.diaryTurn()).isEqualTo(5);
        assertThat(reply.phase()).isEqualTo("complete");
        assertThat(reply.text()).doesNotContain("그전에는");
    }

    @ParameterizedTest
    @ValueSource(strings = {"반가웠어요.", "속상했어요."})
    @DisplayName("감정 없는 사건에는 기분을, 감정 답변에는 계기를 한 번 묻고 답을 듣고 소재를 전환한다")
    void emotionMovesOnAfterAskingCauseOnce(String content) {
        start();
        fake.turnEvent = "친구를 만났다";
        fake.turnQuestion = "그 일에 대해 어떤 생각이 들었어요?";
        fake.turnEmpathy = "그런 마음이 들었군요.";

        ReplyDto first = engine.handle(state, TurnCommand.text("친구를 만났어요."));
        assertThat(first.text()).isEqualTo("그런 마음이 들었군요.\n그때 어떤 기분이 드셨나요?");
        assertThat(state.topicCauseDiscussed()).isFalse();

        fake.turnEvent = null;
        fake.turnEmotionPresent = true;
        ReplyDto cause = engine.handle(state, TurnCommand.text(content));
        assertThat(cause.text()).isEqualTo("그런 마음이 들었군요.\n어떤 일 때문에 그런 마음이 드셨나요?");
        assertThat(state.topicCauseDiscussed()).isTrue();
        assertThat(state.diaryTurn()).isEqualTo(2);

        ReplyDto next = engine.handle(state, TurnCommand.text("친구가 제 얘기를 끝까지 들어줬어요."));
        assertThat(next.phase()).isEqualTo("diary_chat");
        assertThat(next.text()).isEqualTo("그런 마음이 들었군요.\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        assertThat(state.topicMeaningSeen()).isFalse();
        assertThat(state.diaryTurn()).isEqualTo(3);
        assertThat(state.event()).isEqualTo("친구를 만났다");
        assertThat(fake.diaryWriteCalls).isZero();
    }

    @Test
    @DisplayName("감정만 말하면 기분을 되묻지 않고 계기를 한 번 확인한다")
    void emotionWithoutEventMovesOn() {
        start();
        fake.turnEmotionPresent = true;
        fake.turnQuestion = "어떤 일 때문에 그런 마음이 드셨나요?";

        assertThat(engine.handle(state, TurnCommand.text("기분이 좋았어요.")).text())
                .doesNotContain("그랬군요.").contains("들려주신 이야기")
                .endsWith("어떤 일 때문에 그런 마음이 드셨나요?");
        ReplyDto reply = engine.handle(state, TurnCommand.text("기분이 좋았어요."));

        assertThat(state.event()).isNull();
        assertThat(reply.text()).doesNotContain("그랬군요.")
                .endsWith("\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
    }

    @Test
    @DisplayName("3턴 이후 짧게 감정을 답해도 단답 체크인 대신 계기를 묻는다")
    void shortEmotionAsksCauseWithoutCheckin() {
        start();
        fake.turnEvent = "발표를 했다";
        engine.handle(state, TurnCommand.text("발표를 준비했어요."));
        engine.handle(state, TurnCommand.text("사람들 앞에 섰어요."));
        fake.turnEmotionPresent = true;

        ReplyDto reply = engine.handle(state, TurnCommand.text("화"));

        assertThat(reply.phase()).isEqualTo("diary_chat");
        assertThat(reply.options()).isNull();
        assertThat(reply.text()).doesNotContain("그랬군요.")
                .endsWith("\n어떤 일 때문에 그런 마음이 드셨나요?");
        assertThat(state.topicCauseDiscussed()).isTrue();
    }

    @Test
    @DisplayName("이미 두 사건을 이야기했으면 감정 확인 뒤 다른 기존 사건으로 전환한다")
    void emotionAtEventLimitDoesNotOpenThirdEvent() {
        start();
        state.event("발표를 했다");
        state.addSecondaryEvents(List.of("친구와 저녁을 먹었다"));
        fake.turnEvent = "발표를 했다";
        assertThat(engine.handle(state, TurnCommand.text("발표를 했어요.")).text())
                .doesNotContain("그랬군요.").contains("들려주신 이야기")
                .endsWith("그때 어떤 기분이 드셨나요?");
        fake.turnEmotionPresent = true;

        ReplyDto reply = engine.handle(state, TurnCommand.text("떨렸어요."));

        assertThat(reply.text()).endsWith("어떤 일 때문에 그런 마음이 드셨나요?");
        reply = engine.handle(state, TurnCommand.text("사람들 앞에서 발표하는 자리였거든요."));
        assertThat(reply.text()).doesNotContain("그랬군요.")
                .endsWith("\n지금까지 이야기한 것 중에, 다른 이야기를 나눠볼까요?");
        assertThat(state.knownEventCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("감정 없는 첫 발화부터 공감과 기분 질문을 함께 보낸다")
    void keepsEmotionQuestionWhenEmotionIsMissing() {
        start();
        fake.turnEvent = "친구와 저녁을 먹었다";
        fake.turnQuestion = "그때 어떤 기분이 들었어요?";

        ReplyDto reply = engine.handle(state, TurnCommand.text("친구와 저녁을 먹었어요."));

        assertThat(reply.text()).doesNotContain("그랬군요.").contains("들려주신 이야기")
                .endsWith("그때 어떤 기분이 들었어요?");
        assertThat(state.shouldSwitchTopic()).isFalse();
        fake.turnQuestion = "그 일에 대해 어떤 생각이 들었어요?";
        assertThat(engine.handle(state, TurnCommand.text("식사를 같이 했어요.")).text())
                .contains("식사를 같이 했어요.").endsWith("그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("감정을 이미 확인한 뒤 AI 호출이 실패해도 생각 질문으로 돌아가지 않는다")
    void fallbackAfterEmotionDoesNotAskMeaning() {
        start();
        fake.turnEvent = "친구를 만났다";
        fake.turnEmotionPresent = true;
        engine.handle(state, TurnCommand.text("반가웠어요."));
        fake.failDiaryChat = true;

        ReplyDto reply = engine.handle(state, TurnCommand.text("그랬던 것 같아요."));

        assertThat(reply.text()).doesNotContain("그랬군요.")
                .endsWith("\n오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
        assertThat(state.topicMeaningSeen()).isFalse();
    }

    @Test
    @DisplayName("두 번째 소재도 첫 발화에 공감과 감정 질문을 함께 보낸다")
    void eachNewTopicWaitsForFollowup() {
        start();
        fake.turnEvent = "친구를 만났다";
        fake.turnEmotionPresent = true;
        fake.turnEmpathy = "반가운 시간이었군요.";
        engine.handle(state, TurnCommand.text("친구를 만나 반가웠어요."));
        engine.handle(state, TurnCommand.text("정말 즐거웠어요."));
        fake.turnEvent = "팀 발표를 했다";
        fake.turnEmpathy = "긴장되는 자리였군요.";
        fake.turnQuestion = "다른 일은 없었어요?";

        ReplyDto reply = engine.handle(state, TurnCommand.text("발표도 했는데 떨렸어요."));

        assertThat(reply.text()).isEqualTo("긴장되는 자리였군요.\n어떤 일 때문에 그런 마음이 드셨나요?");
        assertThat(state.topicTurn()).isEqualTo(1);
        assertThat(state.diaryTurn()).isEqualTo(3);
        assertThat(state.shouldSwitchTopic()).isFalse();
    }

    @Test
    @DisplayName("3턴의 짧은 새 소재도 체크인 대신 공감과 감정 질문을 보낸다")
    void shortNewTopicGetsEmpathyBeforeCheckin() {
        start();
        fake.turnEvent = "친구를 만났다";
        engine.handle(state, TurnCommand.text("친구를 만났어요."));
        engine.handle(state, TurnCommand.text("같이 밥을 먹었어요."));
        fake.turnEvent = "집으로 돌아왔다";
        fake.turnEmpathy = "집으로 돌아오셨군요.";

        ReplyDto reply = engine.handle(state, TurnCommand.text("집"));

        assertThat(reply.text()).isEqualTo("집으로 돌아오셨군요.\n그때 어떤 기분이 드셨나요?");
        assertThat(reply.options()).isNull();
        assertThat(state.topicTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 사건이 추출돼도 얼버무린 답변은 기존 단답 체크인을 유지한다")
    void vagueNewTopicStillOffersCheckin() {
        start();
        fake.turnEvent = "친구를 만났다";
        engine.handle(state, TurnCommand.text("친구를 만났어요."));
        engine.handle(state, TurnCommand.text("같이 밥을 먹었어요."));
        fake.turnEvent = "집으로 돌아왔다";
        fake.turnVague = true;

        ReplyDto reply = engine.handle(state, TurnCommand.text("글쎄요"));

        assertThat(reply.options()).extracting("label")
                .containsExactly(RetrospectEngine.WRAP_UP, RetrospectEngine.KEEP_TALKING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"사실만 짧게 말했어요.", "그 일정은 취소됐어요.", "발표가 잘됐어요."})
    @DisplayName("짧은 사실·취소 발화에도 AI의 즉시 소재 전환 질문을 보내지 않는다")
    void firstTopicIgnoresImmediateSwitchQuestion(String content) {
        start();
        fake.turnEvent = content;
        fake.turnQuestion = "다른 기억에 남는 일은 뭐였어요?";
        fake.turnEmpathy = "그런 일이 있었군요.";

        assertThat(engine.handle(state, TurnCommand.text(content)).text())
                .isEqualTo("그런 일이 있었군요.\n그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("첫 소재에서 AI가 실패해도 공감과 열린 감정 질문을 함께 보낸다")
    void firstTopicFallbackWaits() {
        start();
        fake.failDiaryChat = true;

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .doesNotContain("그랬군요.").contains("친구를 만났어요.", "들려주신 이야기")
                .endsWith("그때 어떤 기분이 드셨나요?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"함께해서 재밌으셨나요?", "그때 화가 나셨나요?", "많이 뿌듯하셨나요？",
            "그때 어떤 기분이 드셨나요?"})
    @DisplayName("첫 응답은 AI의 맥락 감정 질문을 공감과 함께 보낸다")
    void firstTopicUsesEmotionQuestion(String question) {
        start();
        fake.turnEmpathy = "함께한 시간이 마음에 남으셨군요.";
        fake.turnQuestion = question;

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .isEqualTo(fake.turnEmpathy + "\n" + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"다른 기억에 남는 일은 뭐였어요?", "그 일에 무슨 의미가 있었나요?",
            "어떤 생각이 들었어요?", "왜 화가 나셨나요?", "어떤 일 때문에 속상하셨나요?",
            "무엇 때문에 화가 나셨나요?", "어떤 부분이 마음에 남았나요?",
            "만나면 어떤 기분일 것 같아요?", "앞으로도 집에 있고 싶은 마음인가요?",
            "그때 기분을 말씀해 주세요.", ""})
    @DisplayName("첫 응답의 생각·이유·새 사건 질문과 질문 누락은 열린 감정 질문으로 바꾼다")
    void firstTopicReplacesOtherQuestions(String question) {
        start();
        fake.turnEmpathy = "이야기를 이어가기 쉽지 않으셨겠어요.";
        fake.turnQuestion = question;

        assertThat(engine.handle(state, TurnCommand.text("회의에서 자꾸 말을 끊었어요.")).text())
                .isEqualTo(fake.turnEmpathy + "\n그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("공감이 질문 필드에 중복되어도 공감과 감정 질문 하나만 보낸다")
    void firstTopicUsesSingleQuestionAfterDuplicateEmpathy() {
        start();
        fake.turnEmpathy = "말을 끝내기 어려운 자리였군요.";
        fake.turnQuestion = "말을 끝내기 어려운 자리였군요. 그때 화가 나셨나요? 다른 일은요?";

        assertThat(engine.handle(state, TurnCommand.text("회의에서 자꾸 말을 끊었어요.")).text())
                .isEqualTo(fake.turnEmpathy + "\n그때 화가 나셨나요?");
    }

    @Test
    @DisplayName("공감의 두 번째 문장도 살려 구체적인 마음을 받아 준다")
    void preservesTwoContextualEmpathySentences() {
        start();
        fake.turnEmpathy = "친구가 생일을 챙겨줬군요. 기억하고 챙겨준 마음이 고마우셨겠어요.";

        assertThat(engine.handle(state, TurnCommand.text("친구가 생일을 챙겨줘 고마웠어요.")).text())
                .isEqualTo(fake.turnEmpathy + "\n그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("같은 맥락의 공감 두 문장은 이어 쓰고 다음 질문으로 넘어갈 때만 줄을 나눈다")
    void empathyContextStaysTogetherBeforeNextQuestion() {
        start();
        fake.turnEvent = "친구가 생일을 챙겨줬다";
        engine.handle(state, TurnCommand.text("친구가 생일을 챙겨줬어요."));
        fake.turnEmotionPresent = true;
        fake.turnEmpathy = "친구가 생일을 챙겨줬군요.\n기억해 준 마음이 고마우셨겠어요.";

        assertThat(engine.handle(state, TurnCommand.text("고마웠어요.")).text())
                .isEqualTo("친구가 생일을 챙겨줬군요. 기억해 준 마음이 고마우셨겠어요.\n어떤 일 때문에 그런 마음이 드셨나요?");
    }

    @Test
    @DisplayName("장애 대체 공감도 같은 맥락 안에서 문장마다 줄을 나누지 않는다")
    void fallbackEmpathyKeepsOneContextParagraph() {
        start();
        fake.failDiaryChat = true;

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .contains("이야기를 소중히 듣고 있어요. 그 순간에 담긴 마음")
                .doesNotContain("있어요.\n그 순간")
                .endsWith("그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("공감에서 질문으로 맥락이 바뀔 때만 줄을 나누며 기존 문단은 보존한다")
    void separatesQuestionFromWholeEmpathyContext() {
        assertThat(RetrospectEngine.breakAfterEmpathy("친구가 생일을 챙겨줬군요. 고마우셨겠어요. 다른 일도 있었나요?"))
                .isEqualTo("친구가 생일을 챙겨줬군요. 고마우셨겠어요.\n다른 일도 있었나요?");
        assertThat(RetrospectEngine.breakAfterEmpathy("같은 맥락이에요. 이어지는 문장이에요.\n다음 질문인가요?"))
                .isEqualTo("같은 맥락이에요. 이어지는 문장이에요.\n다음 질문인가요?");
        assertThat(RetrospectEngine.breakAfterEmpathy("친구를 다시 만나셨군요. 오랜만에 함께한 시간이었네요."))
                .isEqualTo("친구를 다시 만나셨군요. 오랜만에 함께한 시간이었네요.");
        assertThat(RetrospectEngine.breakAfterEmpathy("반가운 시간이었겠어요. 다른 일은 있었나요？"))
                .isEqualTo("반가운 시간이었겠어요.\n다른 일은 있었나요？");
    }

    @Test
    @DisplayName("짧은 맞장구 뒤의 구체적인 공감을 남긴다")
    void discardsBareAcknowledgementButKeepsGroundedEmpathy() {
        start();
        fake.turnEmpathy = "그랬군요. 친구가 생일을 기억하고 챙겨준 마음이 고마우셨겠어요.";

        assertThat(engine.handle(state, TurnCommand.text("친구가 생일을 챙겨줘 고마웠어요.")).text())
                .isEqualTo("친구가 생일을 기억하고 챙겨준 마음이 고마우셨겠어요.\n그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("질문 앞의 상황 공감을 복구하고 소재 전환 대신 감정을 묻는다")
    void recoversContextualReflectionFromQuestionField() {
        start();
        fake.turnQuestion = "말을 끝내기 전에 끊겨 이야기를 이어가기 쉽지 않으셨겠어요. 다른 일은 없었나요?";

        assertThat(engine.handle(state, TurnCommand.text("회의에서 말을 계속 끊었어요.")).text())
                .isEqualTo("말을 끝내기 전에 끊겨 이야기를 이어가기 쉽지 않으셨겠어요.\n그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("물음표 없는 요청 문장도 첫 소재의 공감으로 잘못 보내지 않는다")
    void imperativeQuestionIsNotRecoveredAsEmpathy() {
        start();
        fake.turnQuestion = "다른 기억에 남는 일을 들려주세요.";

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .contains("친구를 만났어요.").doesNotContain("들려주세요", "다른 기억", "그랬군요.");
    }

    @Test
    @DisplayName("AI가 맞장구만 줘도 사용자 이야기가 담긴 응답으로 바꾼다")
    void bareAcknowledgementDoesNotBecomeWholeReply() {
        start();
        fake.turnEmpathy = "그랬군요.";

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .contains("친구를 만났어요.").doesNotContain("그랬군요.")
                .endsWith("그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("후속 답변에서도 AI가 실패하면 맥락 공감을 붙여 다음 일을 묻는다")
    void repeatedAiFailureStillReflectsFollowupBeforeSwitching() {
        start();
        fake.failDiaryChat = true;
        engine.handle(state, TurnCommand.text("친구를 만났어요."));

        assertThat(engine.handle(state, TurnCommand.text("반가웠어요.")).text())
                .contains("반가웠어요.").doesNotContain("그랬군요.")
                .endsWith("오늘 하루 중 다른 기억에 남는 일은 뭐였어요?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"어떤 기분이 들었어요?", "어땠나요？"})
    @DisplayName("공감 필드의 질문을 제거하고 감정 질문 하나를 따로 붙인다")
    void firstTopicRejectsQuestionInEmpathy(String empathy) {
        start();
        fake.turnEmpathy = empathy;

        assertThat(engine.handle(state, TurnCommand.text("친구를 만났어요.")).text())
                .doesNotContain("그랬군요.").contains("들려주신 이야기")
                .endsWith("그때 어떤 기분이 드셨나요?");
    }

    @Test
    @DisplayName("슬롯이 1턴에 다 모여도 5턴까지 이어가고, 5턴에서 분기 없이 감정 확인으로 넘어간다(조기 종료 금지).")
    void diaryRunsToFiveTurnsEvenWhenSlotsFillEarly() {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        fake.turnEvent = "면접 스터디에서 팀원이 말을 끊었다";
        fake.turnMeaning = "내 의견이 가볍게 다뤄진 게 계속 걸린다";
        fake.turnEmotionPresent = true; // 1턴에 사건·감정·의미가 다 찬다

        // 슬롯이 다 찼어도 5턴 전에는 종료하지 않고 계속 일기 작성 채팅을 이어간다.
        for (int i = 1; i < 5; i++) {
            ReplyDto mid = engine.handle(state, TurnCommand.text("속상한 일이 있었어요 " + i));
            assertThat(mid.phase()).as("%d턴", i).isEqualTo("diary_chat");
        }

        // 5턴에 도달하면 일기를 정리하고 곧바로 감정 확인을 묻는다.
        ReplyDto reply = engine.handle(state, TurnCommand.text("계속 마음에 걸려요."));
        assertThat(state.diaryTurn()).isEqualTo(5);
        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        assertThat(reply.text()).startsWith("오늘 들려주신 이야기를 소중히 담아둘게요.\n" + RetrospectEngine.EXPLORATION_TRANSITION)
                .contains("팀원이 말을 끊었던 일을 떠올렸을 때");
        assertThat(reply.options()).extracting("label")
                .doesNotContain("감정을 더 알아볼래요", "일기 확인하러 갈래요");
        assertThat(fake.extractCalls).isEqualTo(1);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("슬롯이 안 차도 5턴에 도달하면 있는 것만으로 종료한다.")
    void diaryStopsAtFiveTurns() {
        start();
        fake.failDiaryChat = true; // 추출 없음 → 슬롯이 채워지지 않는다

        for (int i = 1; i <= 4; i++) {
            ReplyDto mid = engine.handle(state, TurnCommand.text("오늘 여러 일이 있었어요 " + i));
            assertThat(mid.phase()).as("%d번째 턴", i).isEqualTo("diary_chat");
        }
        ReplyDto fifth = engine.handle(state, TurnCommand.text("마지막으로 이런 일도 있었어요."));
        // 뽑힌 사건이 없으면 감정 탐색 없이 일기로 마무리한다.
        assertThat(fifth.phase()).isEqualTo("complete");
        assertThat(fifth.wishCard()).isNull();
        assertThat(state.diaryTurn()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"이제 그만할래", "됐어요", "이제 됐어요.", "됐어"})
    @DisplayName("사용자가 그만하려 하면 감정 탐색 없이 즉시 일기로 마무리한다(편지 PRD 14-1).")
    void stopRequestEndsDiaryChat(String content) {
        start();
        fake.events.add(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었던 일", List.of(1, 2)));
        ReplyDto reply = engine.handle(state, TurnCommand.text(content));

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
        assertThat(reply.text()).startsWith("오늘 들려주신 이야기를 소중히 담아둘게요.\n" + RetrospectEngine.EXPLORATION_TRANSITION);
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

        // 5턴을 다 채워 마친 경우도 같다.
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
        assertThat(reply.text()).startsWith("오늘 들려주신 이야기를 소중히 담아둘게요.\n" + RetrospectEngine.EXPLORATION_TRANSITION);
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 더 물어볼 게 없다고 해도 5턴 전에 자동 종료하지 않는다")
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

        // 뽑힌 사건이 없어 감정 탐색 없이 마무리된다 — 여기서 보는 것은 5턴까지 이어갔다는 점이다.
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
