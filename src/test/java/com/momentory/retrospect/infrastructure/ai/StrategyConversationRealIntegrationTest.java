package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectEngine;
import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.application.TurnCommand;
import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyType;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 판단 모델과 문장 생성 모델을 붙여 전략 선택이 대화에 반영되는지 보는 선택 실행 검사.
 * 지어낸 예시 문장만 보낸다. 사용자 대화나 인증 정보를 출력하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class StrategyConversationRealIntegrationTest {

    private RetrospectEngine engine;
    private RetrospectState state;

    @BeforeEach
    void setUp() {
        var gemini = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var geminiClient = new GeminiApiClient(
                new GeminiApiClientConfiguration().geminiRestClient(gemini, ObservationRegistry.NOOP),
                JsonMapper.builder().build(), event -> { }, gemini);
        var jev = new JevApiProperties("https://api.typesafe.ai", System.getenv("TYPESAFE_API_KEY"),
                "jev-1.14.0", Duration.ofSeconds(2), Duration.ofSeconds(3));
        RestClient jevClient = new JevApiClientConfiguration().jevRestClient(jev, ObservationRegistry.NOOP);
        engine = new RetrospectEngine(new SafetyPolicy(),
                new GeminiDiaryChatAssistant(geminiClient, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT)),
                s -> new EmotionExtraction(List.of(), List.of(), null), s -> List.of(), s -> Optional.empty(),
                new JevTurnJudge(jevClient, jev), new StrategyPolicy(0.5, 0.8, 2),
                new LlmUsageLogger(0.10, 0.40), e -> { }, 1, 3);
        state = new RetrospectState("strategy-smoke");
        engine.start(state, StartCommand.today("민지", null));
        state.enableTurnStrategy();
    }

    private ReplyDto say(String text) {
        ReplyDto reply = engine.handle(state, TurnCommand.text(text));
        TurnJudgmentLog log = state.drainJudgmentLogs().getLast();
        System.out.printf("%n나: %s%n  [%s / %s%s]%n바바: %s%n", text,
                log.strategy() == null ? "판단 실패" : log.strategy().type(),
                log.strategy() == null ? "-" : log.strategy().focus(),
                log.strategy() != null && log.strategyApplied() ? "" : " (적용 안 됨)",
                reply.text().replace("\n", "\n      "));
        return reply;
    }

    private static boolean asks(ReplyDto reply) {
        return reply.text().contains("?") || reply.text().contains("？");
    }

    @Test
    void deepensStepByStepAndReflectsSelfExploration() {
        ReplyDto first = say("오늘 팀장한테 또 한 소리 들었어.");
        assertThat(asks(first)).as("사건만 말했으면 자기 쪽으로 묻는다").isTrue();

        say("다들 있는 회의 자리에서 그래서 좀 창피하고 속상했어.");
        say("응, 계속 생각나.");
        ReplyDto deepest = say("속상한 게 지적 자체보다, 인정받고 싶었는데 못 받아서인 것 같아.");

        assertThat(asks(deepest)).as("스스로 짚은 것을 한 번 더 묻는다").isTrue();
        assertThat(state.strategySignals().topicDepth()).isEqualTo(5);
        assertThat(state.strategySignals().lastType()).isEqualTo(StrategyType.EXPLORE);
    }

    @Test
    void stopsAskingAfterARequestToJustListen() {
        say("친구랑 크게 싸웠어.");

        ReplyDto listened = say("질문 말고 그냥 들어줘.");
        ReplyDto still = say("걔가 먼저 약속을 깼는데 내가 예민하다는 거야.");

        assertThat(asks(listened)).isFalse();
        assertThat(asks(still)).as("다음 답변에도 가장 최근의 요청을 따른다").isFalse();
    }

    @Test
    void closesOnAnEndRequestTheRulesDoNotCatch() {
        say("오늘 발표가 있었어.");

        say("오늘 이야기는 이쯤에서 마무리하고 싶어요.");

        assertThat(state.phase().isTerminal()).isTrue();
    }
}
