package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

/** 턴 판단은 켠 요청에서만, 일기 작성 채팅의 사용자 답변마다 한 번 기록되고 대화 흐름은 바꾸지 않는다. */
class RetrospectEngineTurnJudgeTest {

    private FakeAssistant fake;
    private RetrospectState state;
    private final List<String> judged = new ArrayList<>();
    private Optional<TurnJudgment> answer;

    @BeforeEach
    void setUp() {
        fake = new FakeAssistant();
        state = new RetrospectState("s1");
        answer = Optional.of(judgment());
    }

    private RetrospectEngine engine() {
        return new RetrospectEngine(new SafetyPolicy(), fake, fake, fake, fake, (s, text) -> {
            judged.add(text);
            return answer;
        }, new LlmUsageLogger(0.10, 0.40), e -> { }, 1, 3);
    }

    static TurnJudgment judgment() {
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        for (JudgmentItem item : JudgmentItem.values()) {
            items.put(item, new ItemJudgment(item.choices().getFirst(), 0.9,
                    Map.of(item.choices().getFirst(), 0.9, item.choices().getLast(), 0.1)));
        }
        return new TurnJudgment("jev-1.14.0", "v1", items, 1400, 350);
    }

    private ReplyDto start(RetrospectEngine engine) {
        return engine.start(state, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
    }

    @Test
    void recordsOneJudgmentPerDiaryAnswerWhenEnabled() {
        RetrospectEngine engine = engine();
        start(engine);
        state.enableTurnJudging();

        engine.handle(state, TurnCommand.text("팀원이 말을 끊어서 속상했어요."));
        engine.handle(state, TurnCommand.text("제 의견이 가볍게 다뤄진 것 같았어요."));

        assertThat(judged).containsExactly("팀원이 말을 끊어서 속상했어요.", "제 의견이 가볍게 다뤄진 것 같았어요.");
        List<TurnJudgmentLog> logs = state.drainJudgmentLogs();
        assertThat(logs).extracting(TurnJudgmentLog::diaryTurn).containsExactly(1, 2);
        assertThat(logs).allSatisfy(log -> assertThat(log.failed()).isFalse());
        // 기록은 판단한 사용자 발화를 가리킨다.
        List<String> userMessageIds = state.messages().stream().filter(m -> m.isUser())
                .map(m -> m.id()).toList();
        assertThat(logs).extracting(TurnJudgmentLog::messageId).containsExactlyElementsOf(userMessageIds);
        assertThat(state.drainJudgmentLogs()).isEmpty();
    }

    @Test
    void doesNotJudgeWhenNotEnabled() {
        RetrospectEngine engine = engine();
        start(engine);

        engine.handle(state, TurnCommand.text("팀원이 말을 끊어서 속상했어요."));

        assertThat(judged).isEmpty();
        assertThat(state.drainJudgmentLogs()).isEmpty();
    }

    @Test
    void keepsTheSameReplyWhetherOrNotJudging() {
        RetrospectEngine engine = engine();
        start(engine);
        ReplyDto plain = engine.handle(state, TurnCommand.text("팀원이 말을 끊어서 속상했어요."));

        RetrospectState judgedState = new RetrospectState("s2");
        engine.start(judgedState, StartCommand.single("면접 스터디", Emotion.ANGRY, "정민"));
        judgedState.enableTurnJudging();
        ReplyDto withJudge = engine.handle(judgedState, TurnCommand.text("팀원이 말을 끊어서 속상했어요."));

        assertThat(withJudge).isEqualTo(plain);
    }

    @Test
    void recordsAFailedTurnAndKeepsTalking() {
        answer = Optional.empty();
        RetrospectEngine engine = engine();
        start(engine);
        state.enableTurnJudging();

        ReplyDto reply = engine.handle(state, TurnCommand.text("팀원이 말을 끊어서 속상했어요."));

        assertThat(reply.phase()).isEqualTo(Phase.DIARY_CHAT.key());
        assertThat(state.drainJudgmentLogs()).singleElement()
                .satisfies(log -> assertThat(log.failed()).isTrue());
    }

    @Test
    void doesNotSendACrisisUtteranceToTheJudge() {
        RetrospectEngine engine = engine();
        start(engine);
        state.enableTurnJudging();

        engine.handle(state, TurnCommand.text("죽고 싶어요"));

        assertThat(state.phase()).isEqualTo(Phase.SAFETY_HOLD);
        assertThat(judged).isEmpty();
    }
}
