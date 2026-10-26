package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.momentory.retrospect.NeedActionCatalogFixture;
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

    /** 슬롯을 채운 채 6턴(최대)까지 이어가 분기점까지 몰아준다(조기 종료 금지 규칙). */
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
    @DisplayName("슬롯이 1턴에 다 모여도 6턴까지 이어가고, 6턴에서 분기점을 낸다(조기 종료 금지).")
    void diaryRunsToSixTurnsEvenWhenSlotsFillEarly() {
        start();
        fake.turnEvent = "면접 스터디에서 팀원이 말을 끊었다";
        fake.turnMeaning = "내 의견이 가볍게 다뤄진 게 계속 걸린다";
        fake.turnEmotionPresent = true; // 1턴에 사건·감정·의미가 다 찬다

        // 슬롯이 다 찼어도 6턴 전에는 종료하지 않고 계속 일기 작성 채팅을 이어간다.
        for (int i = 1; i < RetrospectState.DIARY_MAX_TURNS; i++) {
            ReplyDto mid = engine.handle(state, TurnCommand.text("속상한 일이 있었어요 " + i));
            assertThat(mid.phase()).as("%d턴", i).isEqualTo("diary_chat");
        }

        // 6턴에 도달하면 분기점을 낸다.
        ReplyDto reply = engine.handle(state, TurnCommand.text("계속 마음에 걸려요."));
        assertThat(reply.phase()).isEqualTo("await_branch");
        assertThat(reply.options()).extracting("label")
                .containsExactly("감정을 더 알아볼래요", "일기 확인하러 갈래요");
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
        assertThat(sixth.phase()).isEqualTo("await_branch");
    }

    @Test
    @DisplayName("사용자가 그만하려 하면 즉시 일기로 정리한다.")
    void stopRequestEndsDiaryChat() {
        start();
        ReplyDto reply = engine.handle(state, TurnCommand.text("이제 그만할래"));

        assertThat(reply.phase()).isEqualTo("await_branch");
        assertThat(fake.diaryWriteCalls).isEqualTo(1);
    }

    // ── 분기점 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("'일기 확인하러 갈래요' → 바람 카드 없이 완료한다.")
    void branchViewCompletesWithoutCard() {
        toBranch();
        ReplyDto reply = engine.handle(state, TurnCommand.option("2"));

        assertThat(reply.phase()).isEqualTo("complete");
        assertThat(reply.done()).isTrue();
        assertThat(reply.diary()).isNotNull();
        assertThat(reply.wishCard()).isNull();
    }

    @Test
    @DisplayName("'감정을 더 알아볼래요' → 추출 감정을 후보로 감정 탐색 1턴을 낸다.")
    void branchExploreStartsExploration() {
        toBranch();
        fake.emotions.add(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, null, null, "말을 끊겼어요", List.of()));
        // extract 는 toBranch 시점에 이미 불렸으므로 미리 넣어야 후보에 반영된다 → 다시 세팅해 재현.
        // (toBranch 에서 emotions 가 비어 있었어도, 이 테스트는 탐색 진입만 확인한다.)

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.phase()).isEqualTo("await_branch");
        assertThat(reply.options()).extracting("label").contains("팀원이 말을 끊었던 일", "일기 보러 갈래요");
        // 후보에서 '직접 적기'·'아직 잘 모르겠어요' 는 뺐다(입력창은 화면이 늘 띄운다, 단일 선택).
        assertThat(reply.options()).extracting("label")
                .doesNotContain("직접 적기", "아직 잘 모르겠어요");
    }

    // ── 감정 탐색 3턴 → 바람 카드 ───────────────────────────────────────

    @Test
    @DisplayName("감정 탐색 3턴을 거치면 바람 카드(작은 행동)까지 만들어 완료한다.")
    void explorationProducesWishCard() {
        fake.actions.add("편안한 상대에게 ‘조언 없이 잠깐 내 이야기를 들어줄래?’라고 부탁하기");
        // 감정을 미리 심어 두려면 extract 결과가 필요하다 — 탐색 진입 전에 세팅.
        fake.emotions.add(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 감정 더 알아보기
        engine.handle(state, TurnCommand.option("1")); // 사건 선택

        // 1턴: 감정 확인 — 첫 후보(화남) 선택
        ReplyDto afterEmotion = engine.handle(state, TurnCommand.option("1"));
        assertThat(afterEmotion.phase()).isEqualTo("emotion_exploration");
        // 2턴: 바람 확인 — 첫 후보 선택
        ReplyDto afterNeed = engine.handle(state, TurnCommand.option("1"));
        assertThat(afterNeed.phase()).isEqualTo("emotion_exploration");
        // 3턴: 작은 행동 — 첫 후보 선택
        ReplyDto done = engine.handle(state, TurnCommand.option("1"));

        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isNotBlank();
        assertThat(done.wishCard().situation()).isNotBlank();
        assertThat(done.wishCard().emotions()).containsExactly("angry");
    }

    @Test
    @DisplayName("작은 행동에는 '오늘은 여기까지'가 없다 — 후보를 고르면 그 행동으로 완료한다.")
    void explorationSmallActionHasNoHereEnd() {
        fake.actions.add("편안한 상대에게 ‘조언 없이 잠깐 내 이야기를 들어줄래?’라고 부탁하기");
        fake.emotions.add(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 탐색 진입
        engine.handle(state, TurnCommand.option("1")); // 사건 선택
        engine.handle(state, TurnCommand.option("1")); // 감정
        ReplyDto actionTurn = engine.handle(state, TurnCommand.option("1")); // 바람 → 행동 턴

        assertThat(actionTurn.options()).extracting("label").doesNotContain("오늘은 여기까지 할래요");

        ReplyDto done = engine.handle(state, TurnCommand.option("1")); // 첫 행동 후보 선택
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction())
                .isEqualTo("편안한 상대에게 ‘조언 없이 잠깐 내 이야기를 들어줄래?’라고 부탁하기");
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

        assertThat(done.phase()).isEqualTo(Phase.AWAIT_BRANCH.key());
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

        engine.handle(state, TurnCommand.option("1"));
        ReplyDto confirm = engine.handle(state, TurnCommand.option("1"));

        assertThat(confirm.text()).startsWith("카페에서 개발함을 떠올렸을 때");
        assertThat(confirm.text()).doesNotContain("개발를").doesNotContain("「");
        assertThat(confirm.text()).doesNotContain("친구와 다툼");
    }

    /** 대표 사건('개발')을 심고 작은 행동 턴까지 몰아준다 — 사건 슬롯은 toBranch 의 '면접 스터디…'. */
    private ReplyDto toSmallActionTurn() {
        fake.events.add(new ExtractedEvent(1, "개발", "카페에서 개발함", List.of(1, 2, 3)));
        fake.emotions.add(new ExtractedEmotion(1, "재밌었어", Emotion.HAPPY, 2,
                EmotionPhase.DURING, "재밌었어", List.of(1)));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 탐색 진입
        engine.handle(state, TurnCommand.option("1")); // 사건 선택
        engine.handle(state, TurnCommand.option("1")); // 감정
        return engine.handle(state, TurnCommand.option("1")); // 바람 → 행동 턴
    }

    @Test
    @DisplayName("작은 행동 질문도 대표 사건 이름을 밝히고, 카드 상황은 사건 슬롯이 아니라 대표 사건이다")
    void smallActionQuestionNamesTheEvent() {
        ReplyDto actionTurn = toSmallActionTurn();

        assertThat(actionTurn.text()).startsWith("카페에서 개발함을 떠올리며,");

        ReplyDto done = engine.handle(state, TurnCommand.option("1"));
        assertThat(done.wishCard().situation()).isEqualTo("카페에서 개발함");
    }

    @Test
    @DisplayName("사건 하나도 선택하면 작은 행동 질문이 선택 사건 요약을 사용한다")
    void smallActionQuestionUsesSelectedSingleEvent() {
        fake.emotions.add(new ExtractedEmotion(1, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 탐색 진입
        engine.handle(state, TurnCommand.option("1")); // 사건 선택
        engine.handle(state, TurnCommand.option("1")); // 감정
        ReplyDto actionTurn = engine.handle(state, TurnCommand.option("1")); // 바람 → 행동 턴

        assertThat(actionTurn.text()).startsWith("팀원이 말을 끊었던 일을 떠올리며");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"없어", "없어.", "딱히..", "몰라ㅠ", "모르겠어", "딱히 없어요!", "없어～", "없어ㅋㅋ",
            "딱히 😅", "없어 "})
    @DisplayName("작은 행동에 거절 답을 적으면 문장부호가 붙어도 행동으로 남기지 않는다 — 카드는 빈칸으로 완료")
    void refusalIsNotSavedAsSmallAction(String refusal) {
        toSmallActionTurn();

        ReplyDto done = engine.handle(state, TurnCommand.text(refusal));

        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isNull();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"동기에게 오늘 찍은 사진 보내기", "잠", "딱히 없지만 산책하기"})
    @DisplayName("작은 행동을 직접 적으면 그 글이 행동으로 남는다 — 한 글자도, 거절어로 시작하는 진짜 행동도")
    void typedSmallActionIsSaved(String action) {
        toSmallActionTurn();

        ReplyDto done = engine.handle(state, TurnCommand.text(action));

        assertThat(done.wishCard().smallAction()).isEqualTo(action);
    }

    @Test
    void emptyCatalogSelectionLeavesInputOpenWithoutGenericFallback() {
        toBranch();
        state.restMethods(List.of("음악 듣기"));
        engine.handle(state, TurnCommand.option("1"));
        engine.handle(state, TurnCommand.option("1"));
        engine.handle(state, TurnCommand.option("1"));
        ReplyDto actionTurn = engine.handle(state, TurnCommand.option("1"));

        assertThat(actionTurn.options()).isEmpty();
        assertThat(actionTurn.done()).isFalse();
        assertThat(actionTurn.phase()).isEqualTo("emotion_exploration");
        assertThat(actionTurn.text()).contains("작은 행동");
        assertThat(actionTurn.text()).contains("직접").doesNotContain("불러오지 못했어요");

        ReplyDto done = engine.handle(state, TurnCommand.text("동기에게 사진 보내기"));
        assertThat(done.wishCard().smallAction()).isEqualTo("동기에게 사진 보내기");
    }

    @Test
    void userCanFinishWithoutChoosingAnActionWhenNoCandidateFits() {
        toSmallActionTurn();

        ReplyDto done = engine.handle(state, TurnCommand.text("없어요"));

        assertThat(done.done()).isTrue();
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isNull();
    }

    @Test
    void typedFixedNeedIsRecognizedForCatalogSelection() {
        fake.emotions.add(new ExtractedEmotion(1, "피곤해", Emotion.TIRED, null, null, "피곤해요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1"));
        engine.handle(state, TurnCommand.option("1"));
        engine.handle(state, TurnCommand.option("1"));

        engine.handle(state, TurnCommand.text(" 휴식 "));

        assertThat(state.needs()).extracting(com.momentory.retrospect.domain.Need::word)
                .containsExactly("휴식");
        assertThat(state.desiredState()).isEqualTo("휴식");
    }

    @Test
    void actionFailureOffersRetryWithoutEndingOrAdvancingExploration() {
        fake.failActions = true;
        ReplyDto failed = toSmallActionTurn();

        assertThat(failed.text()).contains("행동 추천을 불러오지 못했어요", "직접");
        assertThat(failed.options()).extracting(ReplyDto.OptionDto::label).containsExactly("다시 불러오기");
        assertThat(failed.options()).extracting(ReplyDto.OptionDto::id).containsExactly("retry_actions");
        assertThat(failed.done()).isFalse();
        assertThat(failed.wishCard()).isNull();
        assertThat(state.explorationTurn()).isEqualTo(2);
        assertThat(fake.actionCalls).isEqualTo(1);

        // 실패한 턴은 기존 세션 JSON만으로 다시 불러와도 재시도할 수 있어야 한다.
        var codec = new com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec(
                tools.jackson.databind.json.JsonMapper.builder().build());
        state = codec.deserialize(codec.serialize(state));
        fake.failActions = false;
        fake.actions.add("최근 고마웠던 일과 그 이유를 5분 동안 적기");
        ReplyDto retried = engine.handle(state, TurnCommand.option("retry_actions"));

        assertThat(retried.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("최근 고마웠던 일과 그 이유를 5분 동안 적기");
        assertThat(retried.done()).isFalse();
        assertThat(state.explorationTurn()).isEqualTo(2);
        assertThat(fake.actionCalls).isEqualTo(2);

        ReplyDto done = engine.handle(state, TurnCommand.option("1"));
        assertThat(done.wishCard().smallAction()).isEqualTo("최근 고마웠던 일과 그 이유를 5분 동안 적기");
        assertThat(done.wishCard().situation()).isEqualTo("카페에서 개발함");
        assertThat(fake.actionCalls).isEqualTo(2);
    }

    @Test
    void repeatedFailureRemainsRetryableWithoutSavingRetryAsAnAction() {
        fake.failActions = true;
        toSmallActionTurn();

        ReplyDto failedAgain = engine.handle(state, TurnCommand.option("retry_actions"));

        assertThat(failedAgain.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("다시 불러오기");
        assertThat(failedAgain.done()).isFalse();
        assertThat(state.smallAction()).isNull();
        assertThat(state.explorationTurn()).isEqualTo(2);
        assertThat(fake.actionCalls).isEqualTo(2);
    }

    @Test
    void actionFailureStillAcceptsDirectInputWithoutAutomaticRetry() {
        fake.failActions = true;
        toSmallActionTurn();

        ReplyDto done = engine.handle(state, TurnCommand.text("동기에게 사진 보내기"));

        assertThat(done.done()).isTrue();
        assertThat(done.wishCard().smallAction()).isEqualTo("동기에게 사진 보내기");
        assertThat(fake.actionCalls).isEqualTo(1);
    }

    @Test
    void lostSuccessfulRetryResponseCannotSelectAnUnseenActionOnRepeatedRetry() {
        fake.failActions = true;
        toSmallActionTurn();
        fake.failActions = false;
        fake.actions.add("지금 하고 싶은 활동 두 가지를 떠올리고 하나 골라 하기");

        engine.handle(state, TurnCommand.option("retry_actions")); // 서버 성공, 응답은 클라이언트에 도달하지 않음
        var codec = new com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec(
                tools.jackson.databind.json.JsonMapper.builder().build());
        state = codec.deserialize(codec.serialize(state));
        ReplyDto repeated = engine.handle(state, TurnCommand.option("retry_actions"));

        assertThat(repeated.done()).isFalse();
        assertThat(repeated.wishCard()).isNull();
        assertThat(state.smallAction()).isNull();
        assertThat(state.explorationTurn()).isEqualTo(2);
        assertThat(fake.actionCalls).isEqualTo(3);
        assertThat(repeated.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("지금 하고 싶은 활동 두 가지를 떠올리고 하나 골라 하기");

        fake.actions.clear(); // 다음 재시도가 정상 빈 결과여도 완료되거나 행동을 저장하면 안 됨
        ReplyDto noMatch = engine.handle(state, TurnCommand.option("retry_actions"));
        assertThat(noMatch.done()).isFalse();
        assertThat(noMatch.options()).isEmpty();
        assertThat(state.smallAction()).isNull();
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
        toBranch();
        ReplyDto selection = engine.handle(state, TurnCommand.option("1"));
        assertThat(selection.options()).extracting("label").containsExactly(
                "면접에서 답변이 막혔던 일", "친구와 저녁을 먹었던 일", "일기 보러 갈래요");
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
        engine.handle(state, TurnCommand.option("1"));
        assertThat(prompts.actionsPrompt(state, NeedActionCatalogFixture.load()))
                .contains("친구와 저녁을 먹었던 일", "평온함");
        ReplyDto done = engine.handle(state, TurnCommand.option("1"));
        assertThat(done.wishCard().situation()).isEqualTo("친구와 저녁을 먹었던 일");
        assertThat(done.wishCard().emotions()).containsExactly("calm");
    }

    @Test
    void noExtractedEventsCompletesWithoutCard() {
        start();
        engine.handle(state, TurnCommand.text("그만할래"));
        ReplyDto done = engine.handle(state, TurnCommand.option("1"));
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNull();
        assertThat(state.explorationEntered()).isFalse();
    }

    @Test
    void eventSelectionCanExitToDiaryWithoutCard() {
        toBranch();
        engine.handle(state, TurnCommand.option("1"));
        ReplyDto done = engine.handle(state, TurnCommand.option("2"));
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNull();
    }

    @Test
    void selectedEventWithNoEmotionsDoesNotBorrowInferredEmotion() {
        fake.inferredEmotion = Emotion.ANXIOUS;
        toBranch();
        engine.handle(state, TurnCommand.option("1"));
        ReplyDto emotion = engine.handle(state, TurnCommand.option("1"));
        assertThat(emotion.options()).isEmpty();
        assertThat(state.confirmedEmotions()).isEmpty();
    }

}
