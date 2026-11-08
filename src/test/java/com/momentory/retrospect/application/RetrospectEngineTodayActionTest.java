package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.application.event.TodayActionAnswered;
import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.TodayAction;
import com.momentory.retrospect.domain.TodayActionResult;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;

import tools.jackson.databind.json.JsonMapper;

/** 회고 첫 질문 앞의 「오늘의 작은 일」 — 바바의 편지 PRD 4-5 · 14-4. AI 는 부르지 않는다. */
class RetrospectEngineTodayActionTest {

    private static final String ACTION = "5분 동안 숨을 들이쉬고 길게 내쉬기";
    private static final String ORIGINAL_OPENING = "정민님, 오늘 면접 스터디, 어땠어요?";

    private FakeAssistant fake;
    private RetrospectEngine engine;
    private RetrospectState state;
    private final List<Object> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fake = new FakeAssistant();
        engine = new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake,
                new LlmUsageLogger(0.10, 0.40), events::add, 1, 3);
        state = new RetrospectState("s1");
    }

    private ReplyDto start(boolean alreadyDone) {
        return engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"), List.of(),
                TodayAction.of(7L, ACTION, alreadyDone));
    }

    @Test
    void asksWhetherTriedBeforeOriginalOpeningWithoutUsingDiaryTurn() {
        ReplyDto reply = start(false);

        assertThat(reply.text()).isEqualTo("정민님, 오늘 「" + ACTION + "」 해 봤어요?");
        assertThat(reply.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("해 봤어요", "못 했어요", "다른 얘기 할래요");
        assertThat(state.awaitingTodayAction()).isTrue();
        assertThat(state.diaryTurn()).isZero();
    }

    @Test
    void triedAnswerRecordsDoneThenAsksOriginalOpening() {
        start(false);

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.text()).isEqualTo("해 봤군요, 잘했어요. " + ORIGINAL_OPENING);
        assertThat(reply.options()).isNull();
        assertThat(events).containsExactly(new TodayActionAnswered(7L, TodayActionResult.DONE));
        assertThat(state.awaitingTodayAction()).isFalse();
        assertThat(state.diaryTurn()).isZero();
        assertThat(fake.diaryTurnCalls).isZero();
    }

    @Test
    void notTriedAndOtherTopicAreRecordedAsSuch() {
        start(false);
        ReplyDto notTried = engine.handle(state, TurnCommand.option("2"));
        assertThat(notTried.text()).startsWith("못 해도 괜찮아요. 그런 날도 있죠. ");

        state = new RetrospectState("s2");
        start(false);
        ReplyDto other = engine.handle(state, TurnCommand.option("3"));
        assertThat(other.text()).isEqualTo("좋아요, 오늘 이야기부터 해요. " + ORIGINAL_OPENING);

        assertThat(events).containsExactly(new TodayActionAnswered(7L, TodayActionResult.NOT_DONE),
                new TodayActionAnswered(7L, TodayActionResult.SKIPPED));
    }

    @Test
    void alreadyCheckedInShelterAsksHowItWentAndKeepsItDone() {
        ReplyDto reply = start(true);

        assertThat(reply.text()).isEqualTo("오늘 「" + ACTION + "」 해 봤네요. 해 보니 어땠어요?");
        assertThat(reply.options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("좋았어요", "그저 그랬어요", "다른 얘기 할래요");

        ReplyDto answered = engine.handle(state, TurnCommand.option("2"));

        assertThat(answered.text()).isEqualTo("그럴 때도 있죠. " + ORIGINAL_OPENING);
        assertThat(events).containsExactly(new TodayActionAnswered(7L, TodayActionResult.DONE));
    }

    @Test
    void freeTextAnswerStartsDiaryChatWithoutRecording() {
        start(false);

        engine.handle(state, TurnCommand.text("오늘은 회의가 길어서 정신이 없었어요"));

        assertThat(events).isEmpty();
        assertThat(state.awaitingTodayAction()).isFalse();
        assertThat(state.diaryTurn()).isEqualTo(1);
        assertThat(fake.diaryTurnCalls).isEqualTo(1);
    }

    @Test
    void noTodayActionKeepsOriginalOpening() {
        ReplyDto reply = engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"), List.of(),
                null);

        assertThat(reply.text()).isEqualTo(ORIGINAL_OPENING);
        assertThat(state.todayAction()).isNull();
    }

    @Test
    void pendingQuestionSurvivesSessionJsonRoundTrip() {
        start(false);
        RetrospectStateCodec codec = new RetrospectStateCodec(JsonMapper.builder().build());
        state = codec.deserialize(codec.serialize(state));

        ReplyDto reply = engine.handle(state, TurnCommand.option("1"));

        assertThat(reply.text()).isEqualTo("해 봤군요, 잘했어요. " + ORIGINAL_OPENING);
        assertThat(events).containsExactly(new TodayActionAnswered(7L, TodayActionResult.DONE));
    }
}
