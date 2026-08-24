package com.momentory.auth.apple.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "apple.auth")
public record AppleAuthProperties(
        @NotBlank String jwkSetUri,
        @NotBlank String issuer,
        @NotBlank String clientId,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
