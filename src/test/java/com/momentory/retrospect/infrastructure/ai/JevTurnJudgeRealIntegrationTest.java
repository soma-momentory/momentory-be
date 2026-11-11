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
