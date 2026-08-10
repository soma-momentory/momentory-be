package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.domain.assistant.SituationEmbedder;

/**
 * 상황 임베딩 어댑터 — Gemini Developer API {@code embedContent} 직접 호출.
 *
 * <p>{@link GeminiApiClient} 와 같은 방침이다: 무상태 싱글턴, 같은 {@code geminiRestClient}·
 * {@code x-goog-api-key} 를 쓰고, <b>실패해도 예외를 던지지 않는다</b>(빈 결과로 접는다).
 */
@Component
public class GeminiSituationEmbedder implements SituationEmbedder {

    private static final Logger log = LoggerFactory.getLogger(GeminiSituationEmbedder.class);
    private static final String EMBED_PATH = "/v1beta/models/{model}:embedContent";

    private final RestClient restClient;
    private final GeminiApiProperties properties;

    public GeminiSituationEmbedder(
            @Qualifier("geminiRestClient") RestClient restClient,
            GeminiApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String model = properties.embeddingModel();
        try {
            EmbeddingResponse response = restClient.post()
                    .uri(EMBED_PATH, model)
                    .header("x-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", "models/" + model,
                            "content", Map.of("parts", List.of(Map.of("text", text)))))
                    .retrieve()
                    .body(EmbeddingResponse.class);

            List<Double> values = response == null || response.embedding() == null
                    ? null
                    : response.embedding().values();
            if (values == null || values.isEmpty()) {
                log.warn("Gemini 임베딩 응답에 값이 없음");
                return Optional.empty();
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i).floatValue();
            }
            return Optional.of(vector);

        } catch (Exception e) {
            log.warn("Gemini 임베딩 실패: {}", e.toString());
            return Optional.empty();
        }
    }

    /** {@code {"embedding":{"values":[...]}}} */
    record EmbeddingResponse(Embedding embedding) {
        record Embedding(List<Double> values) {
        }
    }
}
