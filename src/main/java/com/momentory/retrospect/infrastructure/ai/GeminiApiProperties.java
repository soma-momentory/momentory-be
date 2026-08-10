package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Gemini Developer API(generativelanguage) 호출 설정.
 *
 * <p>{@code api-key} 는 시크릿이라 기본값이 없다(미설정 시 기동 실패). LLM 응답은 느릴 수 있어
 * {@code read-timeout} 을 길게 둔다. Vertex 유료 모드로 넘어가지 않도록 project-id/location 은
 * 두지 않는다(무료 Developer API 유지).
 */
@Validated
@ConfigurationProperties(prefix = "gemini.api")
public record GeminiApiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotBlank String model,
        @NotNull Double temperature,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
