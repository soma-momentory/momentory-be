package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/** 지어낸 예시 문장만 보내는 선택 실행 검사. 사용자 대화나 인증 정보를 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class JevTurnJudgeRealIntegrationTest {

    private static final String BASE_URL = "https://api.typesafe.ai";

    private static JevTurnJudge judge() {
        Duration connect = Duration.ofSeconds(2);
        Duration read = Duration.ofSeconds(3);
        RestClient restClient = RestClient.builder().baseUrl(BASE_URL)
                .requestFactory(GeminiApiClientConfiguration.requestFactory(connect, read)).build();
        return new JevTurnJudge(restClient, new JevApiProperties(BASE_URL,
                System.getenv("TYPESAFE_API_KEY"), "jev-1.14.0", connect, read));
    }

    private static TurnJudgment judge(String baba, String answer) {
        RetrospectState state = new RetrospectState("jev-smoke");
        state.addAssistantMessage(baba);
        state.addUserMessage(answer);
        return judge().judge(state, answer).orElseThrow();
    }

    @Test
    void readsAnEndRequestAndACorrectedInterpretation() {
        TurnJudgment j = judge("많이 화가 나셨겠어요. 그때 어떤 기분이 드셨나요?",
                "화난 게 아니라 서운한 거야. 오늘은 여기까지 할래.");

        assertThat(j.model()).isEqualTo("jev-1.14.0");
        assertThat(j.inputTokens()).isPositive();
        assertThat(j.of(JudgmentItem.END_INTENT).choice()).isEqualTo("end_request");
        assertThat(j.of(JudgmentItem.REACTION).choice()).isEqualTo("correct_interpretation");
        assertThat(j.of(JudgmentItem.EMOTION_EXPRESSION).choice()).isEqualTo("clear");
    }

    /**
     * 「직전 응답에 대한 반응」이 안 하겠다(저항)와 못 하겠다(답하기 어려워함)를 가르는지 출력해 본다 — 10회 호출.
     * 판단 질문을 고칠 때 사람이 읽어 확인하는 용도라 단정하지 않는다.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "JEV_REACTION_CHECK", matches = "true")
    void printsHowReactionsAreRead() {
        String[][] cases = {
                {"시험에서 어떤 부분이 그렇게 짜증스럽게 느껴지셨나요?", "그냥 전부.", "struggle_to_answer"},
                {"시험의 어떤 부분이 그렇게 짜증스럽게 느껴지셨나요?", "몰라 그냥 짜증나", "struggle_to_answer"},
                {"그때 어떤 기분이 드셨나요?", "잘 모르겠어", "struggle_to_answer"},
                {"그 일이 유독 크게 느껴진 이유가 있을까요?", "글쎄.. 그냥 그래", "struggle_to_answer"},
                {"어떤 점이 제일 속상하셨어요?", "말로 하기가 좀 어렵네", "struggle_to_answer"},
                {"그때 어떤 기분이 드셨나요?", "아 맞다 내일 비 온대요. 우산 챙겨야겠다.", "no_answer_or_topic_change"},
                {"어떤 일 때문에 다투게 되셨나요?", "질문 말고 그냥 들어줘.", "refuse_question"},
                {"많이 화가 나셨겠어요.", "화난 게 아니라 서운한 거야.", "correct_interpretation"},
                {"어떤 점이 제일 분하셨어요?", "공부한 게 하나도 안 나와서 분했어.", "agree_or_elaborate"},
                {"그때 어떤 기분이 드셨나요?", "응", "undeterminable"},
        };
        for (String[] c : cases) {
            var reaction = judge(c[0], c[1]).of(JudgmentItem.REACTION);
            String top = reaction.probabilities().entrySet().stream()
                    .sorted((x, y) -> Double.compare(y.getValue(), x.getValue())).limit(3)
                    .map(e -> e.getKey() + "=" + String.format("%.2f", e.getValue()))
                    .collect(java.util.stream.Collectors.joining(" "));
            System.out.printf("REACTION %s | %s | 기대 %s | 판단 %s (확신 %.2f) | %s%n",
                    reaction.choice().equals(c[2]) ? "O" : "X", c[1], c[2], reaction.choice(),
                    reaction.confidence(), top);
        }
    }

    @Test
    void readsARequestToJustListen() {
        TurnJudgment j = judge("속상하셨겠어요. 어떤 일 때문에 다투게 되셨나요?", "질문 말고 그냥 들어줘.");

        assertThat(j.of(JudgmentItem.REQUESTED_MODE).choice()).isEqualTo("listen");
        assertThat(j.of(JudgmentItem.REACTION).choice()).isEqualTo("refuse_question");
    }

    @Test
    void readsSelfExplorationAsTheDeepestStage() {
        TurnJudgment j = judge("지적받아서 속상하셨군요. 그 속상함은 어떤 느낌이었나요?",
                "속상한 게 지적 자체보다, 인정받고 싶었는데 못 받아서인 것 같아.");

        assertThat(j.of(JudgmentItem.EXPERIENCING_DEPTH).choice()).isEqualTo("stage_5_plus");
        assertThat(j.of(JudgmentItem.END_INTENT).choice()).isNotEqualTo("end_request");
        j.items().values().forEach(item -> assertThat(item.probabilities().values().stream()
                .mapToDouble(Double::doubleValue).sum()).isBetween(0.95, 1.05));
    }
}
