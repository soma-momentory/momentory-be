package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * TypeSafe Jev(판단 모델) 호출 설정.
 *
 * <p>{@code api-key} 는 비어 있어도 된다 — 비어 있으면 판단을 하지 않는다(키가 없는 환경에서도 기동한다).
 * {@code model} 은 별칭({@code jev-latest})이 아니라 고정 버전을 쓴다. 판단 기록을 버전별로 비교해야 하기
 * 때문이다. 판단은 매 턴 문장 생성 앞에 끼어들므로 {@code read-timeout} 을 짧게 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "typesafe.api")
public record JevApiProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
