package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Gemini Developer API 를 때리는 opt-in 스모크. {@code GEMINI_API_KEY} 가 있을 때만 돈다
 * (CI 기본 비활성). 구조화 출력이 실제로 record 로 파싱되는지 확인한다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiApiClientRealIntegrationTest {

    @Test
    void generatesUnderstandingCheckFromRealGemini() {
        GeminiApiProperties properties = new GeminiApiProperties(
                "https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"),
                "gemini-flash-lite-latest",
                0.7, Duration.ofSeconds(3), Duration.ofSeconds(30));
        RestClient restClient = new GeminiApiClientConfiguration().geminiRestClient(properties);
        GeminiApiClient client = new GeminiApiClient(restClient, JsonMapper.builder().build(),
                event -> { }, properties);

        LlmRequest request = LlmRequest.of(new RetrospectState("real-1"), LlmRole.G1,
                "너는 따뜻한 존댓말의 회고 상담사다.",
                """
                사용자가 "면접에서 말이 막혀 너무 불안했어요"라고 했다.
                reflection(사용자의 말을 되비추는 공감 1~2문장)과 situation(행동 카드용 한 줄 요약),
                safetyLevel(none)을 채워라.""");

        Optional<UnderstandingCheck> result = client.generate(request, UnderstandingCheck.class);

        assertThat(result).isPresent();
        assertThat(result.get().reflection()).isNotBlank();
        assertThat(result.get().situation()).isNotBlank();
    }
}
