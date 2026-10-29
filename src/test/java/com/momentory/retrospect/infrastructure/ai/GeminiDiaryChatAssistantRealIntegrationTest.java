package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.momentory.retrospect.domain.RetrospectState;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/** 공개된 예시 문장만 보내는 선택 실행 검사. 사용자 일기나 인증 정보를 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiDiaryChatAssistantRealIntegrationTest {
    @Test
    void firstReplyHasGroundedEmpathyInsteadOfBareAcknowledgement() {
        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var restClient = new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP);
        var client = new GeminiApiClient(restClient, JsonMapper.builder().build(), event -> { }, properties);
        var assistant = new GeminiDiaryChatAssistant(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));
        var state = new RetrospectState("grounded-empathy-smoke");
        state.begin(null, null, null, "정민", null);

        var reply = assistant.turn(state, "오랜만에 만난 친구가 제 생일을 챙겨줬어요. 많이 고마웠어요.");

        assertThat(reply).isPresent();
        assertThat(reply.orElseThrow().empathy()).isNotBlank()
                .containsAnyOf("친구", "생일", "고마", "고맙", "감사")
                .doesNotContain("?", "？")
                .isNotEqualTo("그랬군요.");
    }
}
