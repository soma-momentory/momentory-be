package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 실제 Gemini {@code embedContent} 를 1번 때려 <b>임베딩 차원</b>만 확인하는 opt-in 진단.
 * {@code GEMINI_API_KEY} 가 있을 때만 돈다(CI 기본 비활성).
 *
 * <p>목적: prod 의 {@code situation_embedding vector(768)} 저장이 터지는지의 근원 —
 * {@code GeminiSituationEmbedder} 와 동일한 요청({@code outputDimensionality=768})으로 불렀을 때
 * 모델이 정말 768 로 잘라 주는지 확인한다. 3072 가 오면 이 테스트가 그 숫자로 실패한다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiEmbeddingDimensionRealIntegrationTest {

    private static final int EXPECTED_DIM = 768;

    @Test
    void realEmbeddingHasExpectedDimension() {
        // prod 실제 값과 맞춘다: 임베딩 모델은 GEMINI_EMBEDDING_MODEL(기본 gemini-embedding-001).
        String embeddingModel = System.getenv().getOrDefault("GEMINI_EMBEDDING_MODEL",
                "gemini-embedding-001");
        GeminiApiProperties properties = new GeminiApiProperties(
                "https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"),
                "gemini-flash-lite-latest",
                0.7, Duration.ofSeconds(3), Duration.ofSeconds(30), embeddingModel);
        RestClient restClient = new GeminiApiClientConfiguration().geminiRestClient(properties);

        // GeminiSituationEmbedder 와 동일한 요청 형식으로 raw 호출(차원 가드에 걸러지기 전 원본을 본다).
        EmbeddingResponse response = restClient.post()
                .uri("/v1beta/models/{model}:embedContent", embeddingModel)
                .header("x-goog-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", "models/" + embeddingModel,
                        "content", Map.of("parts", List.of(Map.of("text",
                                "면접에서 말이 막혀 너무 불안했어요"))),
                        "outputDimensionality", EXPECTED_DIM))
                .retrieve()
                .body(EmbeddingResponse.class);

        int actualDim = response == null || response.embedding() == null
                || response.embedding().values() == null
                ? -1
                : response.embedding().values().size();
        System.out.println("[진단] 임베딩 모델=" + embeddingModel + " 실제 차원=" + actualDim
                + " (기대 " + EXPECTED_DIM + ")");

        assertThat(actualDim)
                .as("gemini 가 outputDimensionality=%d 를 지키는지 — 아니면 이 숫자가 실제 차원",
                        EXPECTED_DIM)
                .isEqualTo(EXPECTED_DIM);
    }

    record EmbeddingResponse(Embedding embedding) {
        record Embedding(List<Double> values) {
        }
    }
}
