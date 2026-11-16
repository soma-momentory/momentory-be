package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.StrategyReply;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 규칙 v11 의 두 흐름을 실제 문장 생성 모델로 돌려 출력하는 선택 실행 검사 — 사람이 읽고 판단한다. 지어낸 예시 문장만 보낸다.
 *
 * <ol>
 * <li>「내가 이상해?」처럼 자기 편을 들어 주길 바라는 물음 — askedForSide 가 true 이고, 받아주는 말이 사용자 편에서 답하며
 *     상대를 탓하지 않는가. 다른 관점 자체를 청한 말과 평범한 답은 false 인가.
 * <li>다른 기억을 물은 뒤 앞 이야기를 이어 말함 — 새 이야기로 보지 않고(newTopic false) 앞 이야기를 이어 묻는가.
 *     새 이야기를 꺼냈을 때는 newTopic 이 true 인가.
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STRATEGY_V11_CHECK", matches = "true")
class StrategySideAndContinueRealIntegrationTest {

    private static final String OPEN = "오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?";
    private static final String OTHER = "오늘 하루 중 다른 기억에 남는 일은 뭐였어요?";

    /** @param lines 바바와 사용자가 번갈아 한 말. 마지막이 판단할 사용자 답변이다 */
    private record Case(String name, TurnStrategy strategy, int repeat, List<String> lines) {
    }

    private static final TurnStrategy REFRAME = new TurnStrategy(StrategyType.REFRAME, QuestionFocus.OPEN, false);
    private static final TurnStrategy YIELD_NO_ANSWER = new TurnStrategy(StrategyType.YIELD, QuestionFocus.OPEN, false,
            "no_answer_or_topic_change");
    private static final TurnStrategy EXPLORE = new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OPEN, false);

    private static final List<String> MONEY = List.of(OPEN,
            "친구랑 밥 먹고 내가 계산했는데, 걔가 돈 보낼 때마다 100원씩 빼고 보내서 화냈더니 나보고 쪼잔하대.",
            "친구가 돈을 덜 보내서 화가 나셨군요.\n쪼잔하다는 말을 들었을 때 어떤 마음이 들었나요?");
    private static final List<String> FRIEND = List.of(OPEN,
            "친구한테 취업 준비 힘들다고 했는데 아직 어린데 뭘 걱정하냐고 웃더라. 서운했어.",
            "가볍게 넘기는 것 같아 서운하셨군요.\n그때 스스로는 어떤 마음이 들었어요?",
            "내가 너무 예민한 건가 싶기도 했어.",
            "예민한 건 아닌가 하는 생각도 드셨군요.\n" + OTHER);

    private static List<String> then(List<String> before, String user) {
        var all = new java.util.ArrayList<>(before);
        all.add(user);
        return all;
    }

    private static final List<Case> CASES = List.of(
            new Case("편들기 · 재해석 방침", REFRAME, 3, then(MONEY, "아니 내가 이상한 거야?")),
            new Case("편들기 · 물러서기 방침", YIELD_NO_ANSWER, 3, then(MONEY, "아니 내가 이상한 거야?")),
            new Case("편들기 · 예민한가", REFRAME, 3, List.of(OPEN,
                    "회사에서 내가 만든 기능에 동료가 피드백을 줬는데 기분이 좀 상했어.",
                    "피드백을 받고 기분이 상하셨군요.\n그때 어떤 마음이 들었어요?",
                    "혹시 내가 피드백을 너무 개인적으로 받아들인 걸까?")),
            new Case("대조 · 다른 관점 자체를 청함", REFRAME, 1, then(MONEY, "이걸 다른 시각으로 보면 어떨까?")),
            new Case("대조 · 평범한 답", EXPLORE, 1, then(MONEY, "그냥 좀 억울했어.")),
            new Case("다른 기억 뒤 앞 이야기 이어감", EXPLORE.withAfterOtherMemory(true), 3,
                    then(FRIEND, "오늘은 그게 제일 남아. 그냥 친구한테 이해받고 싶었던 것 같아.")),
            new Case("다른 기억 뒤 새 이야기", EXPLORE.withAfterOtherMemory(true), 3,
                    then(FRIEND, "아 그리고 저녁에 엄마랑 통화했어.")));

    @Test
    void printsBothFlows() throws InterruptedException {
        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var client = new GeminiApiClient(
                new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP),
                JsonMapper.builder().build(), event -> { }, properties);
        var writer = new GeminiStrategyReplyWriter(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));

        for (Case c : CASES) {
            for (int run = 1; run <= c.repeat(); run++) {
                RetrospectState state = new RetrospectState("v11-" + c.name() + "-" + run);
                state.begin(null, null, null, "민지", null);
                for (int i = 0; i < c.lines().size(); i++) {
                    if (i % 2 == 0) {
                        state.addAssistantMessage(c.lines().get(i));
                    } else {
                        state.addUserMessage(c.lines().get(i));
                    }
                }
                String user = c.lines().getLast();
                Optional<StrategyReply> turn = writer.write(state, user, c.strategy());
                System.out.printf("V11 [%s] #%d 나: %s%n    받음: %s%n    질문: %s%n"
                                + "    편들기=%s 새 이야기=%s 더 물을 것 없음=%s%n", c.name(), run, user,
                        turn.map(StrategyReply::ack).orElse("(실패)"), turn.map(StrategyReply::question).orElse("(실패)"),
                        turn.map(r -> String.valueOf(r.askedForSide())).orElse("-"),
                        turn.map(r -> String.valueOf(r.newTopic())).orElse("-"),
                        turn.map(r -> String.valueOf(r.nothingMoreToAsk())).orElse("-"));
                Thread.sleep(500);
            }
        }
    }
}
