package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.momentory.retrospect.application.metering.CallLog;
import com.momentory.retrospect.application.metering.LlmCallRecorded;
import com.momentory.retrospect.domain.safety.PromptLeakGuard;

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
        return attempt(request, type, GeminiResponseSchemas.forType(type)).value();
    }

    /**
     * 한 번 호출한 결과 — 실패면 {@code value} 가 비고, {@code retryable} 은 다시 불러 볼 만한
     * 실패(429 · 5xx · 시간 초과 · 연결 실패)인지 알려 준다. 안전 차단 · 유출 · 빈 응답은 다시
     * 불러도 같을 가능성이 커서 false 다.
     */
    public record Attempt<T>(Optional<T> value, boolean retryable) {

        static <T> Attempt<T> failed(boolean retryable) {
            return new Attempt<>(Optional.empty(), retryable);
        }
    }

    /**
     * {@link #generate} 와 같은 호출이지만 응답 스키마를 호출하는 쪽이 넘기고, 재시도 판단에 쓸
     * 실패 종류를 함께 돌려준다. 예외는 던지지 않는다.
     */
    public <T> Attempt<T> attempt(LlmRequest request, Class<T> type, Map<String, Object> schema) {
        long startedAt = System.nanoTime();
        try {
            GeminiResponse response = restClient.post()
                    .uri(GENERATE_PATH, properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(request, schema))
                    .retrieve()
                    .body(GeminiResponse.class);

            long elapsedMs = elapsedMillis(startedAt);

            // 출력 재검열(추가 호출 0회) — 생성 응답에 함께 온 안전 등급을 본다. 하드 차단이면
            // 텍스트를 쓰지 않고 폴백(풀 대체)으로 되돌린다. 위기 대화 정상 응답이 확률만 높은
            // 경우는 막지 않는다(GeminiResponse#blockedBySafety 참고).
            if (response != null && response.blockedBySafety()) {
                log.warn("Gemini 응답이 안전 필터에 막힘 role={} session={} reason={}",
                        request.role().key(), request.sessionId(), response.safetyBlockReason());
                return Attempt.failed(false);
            }

            String text = response == null ? null : response.firstText();
            if (text == null || text.isBlank()) {
                log.warn("Gemini 응답에 구조화 출력이 없음 role={} session={}",
                        request.role().key(), request.sessionId());
                return Attempt.failed(false);
            }
            // 출력 층 유출 검사(추가 호출 0회) — 인젝션이 하드닝을 뚫어 모델이 시스템 프롬프트를
            // 뱉었으면 유저에게 보이지 않고 폴백으로 되돌린다. PromptGuard(입력 층)의 출력 짝.
            if (PromptLeakGuard.leaks(text)) {
                log.warn("Gemini 응답에 시스템 프롬프트 유출 흔적 role={} session={}",
                        request.role().key(), request.sessionId());
                return Attempt.failed(false);
            }

            T entity = objectMapper.readValue(text, type);
            publish(request, response, elapsedMs);
            return new Attempt<>(Optional.ofNullable(entity), false);

        } catch (Exception e) {
            log.warn("Gemini 호출 실패 role={} session={} ({}ms): {}",
                    request.role().key(), request.sessionId(), elapsedMillis(startedAt),
                    e.toString());
            return Attempt.failed(isRetryable(e));
        }
    }

    /**
     * 읽기 한도만 다른 같은 클라이언트 — 편지처럼 호출마다 기다릴 수 있는 시간이 다를 때 쓴다.
     * 안전 차단 · 유출 검사 · 사용량 기록은 그대로 거친다.
     */
    public GeminiApiClient withReadTimeout(Duration readTimeout) {
        RestClient client = restClient.mutate()
                .requestFactory(GeminiApiClientConfiguration.requestFactory(
                        properties.connectTimeout(), readTimeout))
                .build();
        return new GeminiApiClient(client, objectMapper, events, properties);
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return e instanceof ResourceAccessException;
    }

    private Map<String, Object> buildBody(LlmRequest request, Map<String, Object> schema) {
        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("temperature", properties.temperature());
        generationConfig.put("responseMimeType", "application/json");
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
