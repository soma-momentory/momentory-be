package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

class JevTurnJudgeTest {

    private static final String ANSWERS = """
            "end_intent": {"type":"choice","choice":"continue","confidence":0.82,
                "probabilities":{"end_request":0.0,"continue":0.88,"unclear":0.12}},
            "requested_mode": {"type":"choice","choice":"none","confidence":0.99,
                "probabilities":{"listen":0.01,"explore":0.0,"other_view":0.0,"advice":0.0,"none":0.99,"mixed":0.0}},
            "emotion_expression": {"type":"choice","choice":"clear","confidence":0.98,
                "probabilities":{"clear":0.99,"vague":0.01,"none":0.0}},
            "event_info": {"type":"choice","choice":"specific","confidence":0.58,
                "probabilities":{"specific":0.72,"partial":0.27,"none":0.01}},
            "reaction": {"type":"choice","choice":"agree_or_elaborate","confidence":0.91,
                "probabilities":{"agree_or_elaborate":0.92,"first_turn":0.07,"undeterminable":0.01}},
            """;
    private static final String DEPTH = """
            "experiencing_depth": {"type":"choice","choice":"stage_3","confidence":0.94,
                "probabilities":{"stage_1_2":0.01,"stage_3":0.95,"stage_4":0.04,"stage_5_plus":0.0,"undeterminable":0.0}}
            """;

    private MockRestServiceServer server;
    private RestClient restClient;
    private RetrospectState state;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://jev.test");
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        state = new RetrospectState("s1");
        state.addAssistantMessage("오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?");
        state.addUserMessage("발표 망쳐서 허무해.");
    }

    private JevTurnJudge judge(String apiKey) {
        return new JevTurnJudge(restClient, new JevApiProperties("https://jev.test", apiKey, "jev-1.14.0",
                Duration.ofSeconds(2), Duration.ofSeconds(3)));
    }

    private static String response(String answers) {
        return "{\"model\":\"jev-1.14.0\",\"answers\":{" + answers
                + "},\"usage\":{\"input_tokens\":1375,\"output_tokens\":40}}";
    }

    @Test
    void sendsSixQuestionsWithPriorTalkAndReadsEveryItem() {
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("jev-1.14.0"))
                .andExpect(jsonPath("$.state.사용자의_마지막_발화").value("발표 망쳐서 허무해."))
                // 방금 답변은 앞선 대화에 다시 넣지 않는다.
                .andExpect(jsonPath("$.state.대화.length()").value(1))
                .andExpect(jsonPath("$.state.대화[0].화자").value("바바"))
                .andExpect(jsonPath("$.questions.length()").value(6))
                .andExpect(jsonPath("$.questions.experiencing_depth.type").value("choice"))
                .andExpect(jsonPath("$.questions.reaction.criteria.refuse_question").isNotEmpty())
                .andRespond(withSuccess(response(ANSWERS + DEPTH), MediaType.APPLICATION_JSON));

        Optional<TurnJudgment> judgment = judge("test-key").judge(state, "발표 망쳐서 허무해.");

        assertThat(judgment).isPresent();
        TurnJudgment j = judgment.get();
        assertThat(j.model()).isEqualTo("jev-1.14.0");
        assertThat(j.questionsVersion()).isEqualTo(JevJudgmentQuestions.VERSION);
        assertThat(j.inputTokens()).isEqualTo(1375);
        assertThat(j.of(JudgmentItem.EXPERIENCING_DEPTH).choice()).isEqualTo("stage_3");
        assertThat(j.of(JudgmentItem.EXPERIENCING_DEPTH).confidence()).isEqualTo(0.94);
        assertThat(j.of(JudgmentItem.EVENT_INFO).probabilities()).containsEntry("partial", 0.27);
        assertThat(j.of(JudgmentItem.END_INTENT).choice()).isEqualTo("continue");
        server.verify();
    }

    @Test
    void stripsChoiceLinesAndKeepsOnlyRecentTalk() {
        RetrospectState longTalk = new RetrospectState("s2");
        for (int i = 0; i < 6; i++) {
            longTalk.addAssistantMessage("질문 " + i + "\n  1. 이제 정리할래요\n  2. 조금 더 이야기할래요");
            longTalk.addUserMessage("답 " + i);
        }
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andExpect(jsonPath("$.state.대화.length()").value(JevTurnJudge.HISTORY_MESSAGES))
                .andExpect(jsonPath("$.state.대화[-1].말").value("질문 5"))
                .andRespond(withSuccess(response(ANSWERS + DEPTH), MediaType.APPLICATION_JSON));

        assertThat(judge("test-key").judge(longTalk, "답 5")).isPresent();
        server.verify();
    }

    @Test
    void doesNotCallWithoutAnApiKey() {
        assertThat(judge("").judge(state, "발표 망쳐서 허무해.")).isEmpty();
        assertThat(judge(null).judge(state, "발표 망쳐서 허무해.")).isEmpty();
        server.verify();
    }

    @Test
    void returnsEmptyWhenAnItemIsMissing() {
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andRespond(withSuccess(response(ANSWERS.strip().replaceAll(",$", "")),
                        MediaType.APPLICATION_JSON));

        assertThat(judge("test-key").judge(state, "발표 망쳐서 허무해.")).isEmpty();
    }

    @Test
    void returnsEmptyWhenAChoiceIsNotOneWeDefined() {
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andRespond(withSuccess(response(ANSWERS + DEPTH.replace("\"choice\":\"stage_3\"",
                        "\"choice\":\"stage_9\"")), MediaType.APPLICATION_JSON));

        assertThat(judge("test-key").judge(state, "발표 망쳐서 허무해.")).isEmpty();
    }

    @Test
    void returnsEmptyOnServerErrorAndRateLimit() {
        server.expect(requestTo("https://jev.test/v1/systemone")).andRespond(withServerError());
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(judge("test-key").judge(state, "발표 망쳐서 허무해.")).isEmpty();
        assertThat(judge("test-key").judge(state, "발표 망쳐서 허무해.")).isEmpty();
        server.verify();
    }

    @Test
    void returnsEmptyOnUnreadableBody() {
        server.expect(requestTo("https://jev.test/v1/systemone"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThat(judge("test-key").judge(state, "발표 망쳐서 허무해.")).isEmpty();
    }
}
