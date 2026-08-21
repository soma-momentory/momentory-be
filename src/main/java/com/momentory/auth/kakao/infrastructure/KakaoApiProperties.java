package com.momentory.auth.kakao.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "kakao.api")
public record KakaoApiProperties(
        @NotBlank String baseUrl,
        @NotNull Long appId,
        @NotBlank String adminKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
