package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectEngine;
import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.application.StrategyDiaryChat;
import com.momentory.retrospect.application.TurnCommand;
import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고정 시나리오 묶음 — 실제 판단 모델과 문장 생성 모델을 붙여 여섯 가지 대화를 끝까지 돌리고 전체를 출력한다.
 *
 * <p>대화 한 건을 보고 규칙을 고치면 다른 대화에서 깨진다. 규칙이나 프롬프트를 바꿀 때는 이 묶음을 통째로 돌려 사람이 읽는다.
 * 지어낸 문장만 보내고, 단정하지 않는다(모델 출력이 매번 달라서). 한 번에 판단 · 문장 생성을 각각 약 26회 부른다.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STRATEGY_SCENARIO_CHECK", matches = "true")
class StrategyScenarioRealIntegrationTest {

    private record Scenario(String name, List<String> lines) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("이유까지 말한 뒤 새 이야기", List.of("오늘 시험 쳤는데 망한 거 같아", "우울하고 분했어",
                    "약간 실망했어. 열심히 공부했는데 잘 풀지 못했거든", "오늘 엄마랑 통화했는데 좋았어.",
                    "그냥 시험 망친 거 같다고 토로했지..", "엄마랑 얘기하고 마음이 안정됐어.")),
            new Scenario("바바의 말에 이의를 닮", List.of("오늘 시험 쳤는데 망한 거 같아", "우울했어. 분하고",
                    "약간 한심하고 실망했어", "뭐?", "그 정도는 아니야")),
            new Scenario("답하기 어려워함", List.of("오늘 발표했어", "그냥 그랬어", "잘 모르겠어", "그냥 전부.")),
            new Scenario("들어 달라고 함", List.of("친구랑 크게 싸웠어.", "질문 말고 그냥 들어줘.",
                    "걔가 먼저 약속을 깼는데 내가 예민하다는 거야.", "진짜 어이없었어.")),
            new Scenario("짧은 동의만 이어짐", List.of("오늘 팀장한테 또 한 소리 들었어.", "맞아", "응", "그렇지")),
            new Scenario("고쳐 말한 뒤 끝냄", List.of("오늘 면접 봤는데 떨어진 거 같아서 허무해",
                    "아니 슬픈 건 아니고 허무한 거야", "오늘 이야기는 이쯤에서 마무리하고 싶어요.")));

    @Test
    void printsEveryScenario() {
        var gemini = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var geminiClient = new GeminiApiClient(
                new GeminiApiClientConfiguration().geminiRestClient(gemini, ObservationRegistry.NOOP),
                JsonMapper.builder().build(), event -> { }, gemini);
        var jev = new JevApiProperties("https://api.typesafe.ai", System.getenv("TYPESAFE_API_KEY"),
                "jev-1.14.0", Duration.ofSeconds(2), Duration.ofSeconds(3));
        RestClient jevClient = new JevApiClientConfiguration().jevRestClient(jev, ObservationRegistry.NOOP);
        var prompts = new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT);
        var judge = new JevTurnJudge(jevClient, jev);
        var usage = new LlmUsageLogger(0.10, 0.40);
        RetrospectEngine engine = new RetrospectEngine(new SafetyPolicy(),
                new GeminiDiaryChatAssistant(geminiClient, prompts),
                s -> new EmotionExtraction(List.of(), List.of(), null), s -> List.of(), s -> Optional.empty(), judge,
                new StrategyDiaryChat(judge, new StrategyPolicy(0.5, 0.8, 2, 8),
                        new GeminiStrategyReplyWriter(geminiClient, prompts), usage, 12),
                usage, e -> { }, 1, 3);

        for (Scenario scenario : SCENARIOS) {
            RetrospectState state = new RetrospectState("scenario");
            ReplyDto opening = engine.start(state, StartCommand.today("민지", null));
            state.enableTurnStrategy();
            System.out.printf("%nSCN ===== %s =====%nSCN 바바: %s%n", scenario.name(), opening.text());
            for (String line : scenario.lines()) {
                if (state.phase() != Phase.DIARY_CHAT) {
                    System.out.printf("SCN (대화가 끝나 남은 발화는 보내지 않음: %s)%n", line);
                    continue;
                }
                ReplyDto reply = engine.handle(state, TurnCommand.text(line));
                List<TurnJudgmentLog> logs = state.drainJudgmentLogs();
                System.out.printf("SCN 나: %s%n", line);
                if (!logs.isEmpty()) {
                    TurnJudgmentLog log = logs.getLast();
                    String read = log.failed() ? "판단 실패" : "반응=%s(%.2f) 종료=%s(%.2f) 요청=%s 깊이=%s(%.2f)".formatted(
                            log.judgment().of(JudgmentItem.REACTION).choice(),
                            log.judgment().of(JudgmentItem.REACTION).confidence(),
                            log.judgment().of(JudgmentItem.END_INTENT).choice(),
                            log.judgment().of(JudgmentItem.END_INTENT).confidence(),
                            log.judgment().of(JudgmentItem.REQUESTED_MODE).choice(),
                            log.judgment().of(JudgmentItem.EXPERIENCING_DEPTH).choice(),
                            log.judgment().of(JudgmentItem.EXPERIENCING_DEPTH).confidence());
                    System.out.printf("SCN    [%s] → %s/%s%s%s 이야기 %d번째%n", read,
                            log.strategy() == null ? "-" : log.strategy().type(),
                            log.strategy() == null ? "-" : log.strategy().focus(),
                            log.generationFailed() ? " (문장 생성 실패)" : "",
                            log.strategyApplied() ? "" : " (적용 안 됨)", state.strategySignals().topicNumber());
                }
                System.out.printf("SCN 바바: %s%n", reply.text().replace("\n", "\nSCN       "));
                if (reply.options() != null && !reply.options().isEmpty() && state.phase() == Phase.DIARY_CHAT) {
                    // 마무리 제안 — 더 이야기하겠다고 하고 남은 발화를 잇는다.
                    ReplyDto more = engine.handle(state, TurnCommand.option("2"));
                    System.out.printf("SCN 나: (조금 더 이야기할래요)%nSCN 바바: %s%n", more.text());
                }
            }
        }
    }
}
