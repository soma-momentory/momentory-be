package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.momentory.retrospect.application.metering.CallLog;
import com.momentory.retrospect.application.metering.LlmCallRecorded;

import tools.jackson.databind.ObjectMapper;

/**
 * LLM 호출 단일 진입점 — Gemini Developer API {@code generateContent} 직접 호출.
 *
 * <p>원본은 Spring AI {@code ChatClient.responseEntity(Class)} 로 구조화 출력을 받았다.
 * Spring Boot 4.1 은 Spring AI 1.1 과 호환되지 않아, 여기서는 RestClient 로 REST 를 직접
 * 부르고 {@code responseMimeType=application/json} + 손수 만든 {@code responseSchema} 로
 * 구조화 출력을 유도한 뒤 Jackson 으로 {@code parts[0].text} 를 record 로 역직렬화한다.
 *
 * <p>무상태 싱글턴이다. 세션별 로그는 들고 있지 않고 {@link LlmCallRecorded} 이벤트로 던진다.
 */
@Component
public class GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiApiClient.class);
    private static final String GENERATE_PATH = "/v1beta/models/{model}:generateContent";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final GeminiApiProperties properties;

    public GeminiApiClient(
            @Qualifier("geminiRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            GeminiApiProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.events = events;
        this.properties = properties;
    }

    /**
     * 구조화 출력을 받아 온다.
     *
     * <p>실패해도 예외를 던지지 않는다 — AI 장애로 회고 대화가 끊기면 안 된다. 호출자는 빈 결과를
     * 받아 PDF 원형 폴백으로 되돌아간다.
     *
     * @return 실패 시 {@link Optional#empty()}
     */
    public <T> Optional<T> generate(LlmRequest request, Class<T> type) {
        long startedAt = System.nanoTime();
        try {
            GeminiResponse response = restClient.post()
                    .uri(GENERATE_PATH, properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(request, type))
                    .retrieve()
                    .body(GeminiResponse.class);

            long elapsedMs = elapsedMillis(startedAt);
            String text = response == null ? null : response.firstText();
            if (text == null || text.isBlank()) {
                log.warn("Gemini 응답에 구조화 출력이 없음 role={} session={}",
                        request.role().key(), request.sessionId());
                return Optional.empty();
            }
            T entity = objectMapper.readValue(text, type);
            publish(request, response, elapsedMs);
            return Optional.ofNullable(entity);

        } catch (Exception e) {
            log.warn("Gemini 호출 실패 role={} session={} ({}ms): {}",
                    request.role().key(), request.sessionId(), elapsedMillis(startedAt),
                    e.toString());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildBody(LlmRequest request, Class<?> type) {
        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("temperature", properties.temperature());
        generationConfig.put("responseMimeType", "application/json");
        Map<String, Object> schema = GeminiResponseSchemas.forType(type);
        if (schema != null) {
            generationConfig.put("responseSchema", schema);
        }
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", request.user())))),
                "generationConfig", generationConfig);
    }

    private void publish(LlmRequest request, GeminiResponse response, long elapsedMs) {
        GeminiResponse.UsageMetadata usage = response == null ? null : response.usageMetadata();
        CallLog callLog = new CallLog(
                request.role().key(),
                resolveModel(response),
                request.phase(),
                intOf(usage == null ? null : usage.promptTokenCount()),
                intOf(usage == null ? null : usage.candidatesTokenCount()),
                intOf(usage == null ? null : usage.cachedContentTokenCount()),
                elapsedMs,
                false);
        events.publishEvent(new LlmCallRecorded(request.sessionId(), callLog));
    }

    private String resolveModel(GeminiResponse response) {
        if (response != null && response.modelVersion() != null
                && !response.modelVersion().isBlank()) {
            return response.modelVersion();
        }
        return properties.model();
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
