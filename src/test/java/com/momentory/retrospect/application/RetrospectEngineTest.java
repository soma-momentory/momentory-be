package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
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
        fake.emotions.add(new ExtractedEmotion(null, "무시당한 느낌", Emotion.ANGRY, null, null, "말을 끊겼어요", List.of()));
        // extract 는 toBranch 시점에 이미 불렸으므로 미리 넣어야 후보에 반영된다 → 다시 세팅해 재현.
        // (toBranch 에서 emotions 가 비어 있었어도, 이 테스트는 탐색 진입만 확인한다.)

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.phase()).isEqualTo("emotion_exploration");
        // 후보에서 '직접 적기'·'아직 잘 모르겠어요' 는 뺐다(입력창은 화면이 늘 띄운다, 단일 선택).
        assertThat(reply.options()).extracting("label")
                .doesNotContain("직접 적기", "아직 잘 모르겠어요");
    }

    // ── 감정 탐색 3턴 → 바람 카드 ───────────────────────────────────────

    @Test
    @DisplayName("감정 탐색 3턴을 거치면 바람 카드(작은 행동)까지 만들어 완료한다.")
    void explorationProducesWishCard() {
        // 감정을 미리 심어 두려면 extract 결과가 필요하다 — 탐색 진입 전에 세팅.
        fake.emotions.add(new ExtractedEmotion(null, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 감정 더 알아보기

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
        fake.actions.add("따뜻한 물 한 잔 마시기");
        fake.emotions.add(new ExtractedEmotion(null, "무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요", List.of()));
        toBranch();
        engine.handle(state, TurnCommand.option("1")); // 탐색 진입
        engine.handle(state, TurnCommand.option("1")); // 감정
        ReplyDto actionTurn = engine.handle(state, TurnCommand.option("1")); // 바람 → 행동 턴

        assertThat(actionTurn.options()).extracting("label").doesNotContain("오늘은 여기까지 할래요");

        ReplyDto done = engine.handle(state, TurnCommand.option("1")); // 첫 행동 후보 선택
        assertThat(done.phase()).isEqualTo("complete");
        assertThat(done.wishCard()).isNotNull();
        assertThat(done.wishCard().smallAction()).isEqualTo("따뜻한 물 한 잔 마시기");
    }
}
