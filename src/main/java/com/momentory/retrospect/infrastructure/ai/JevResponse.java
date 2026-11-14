package com.momentory.retrospect.infrastructure.ai;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Jev {@code POST /v1/systemone} 응답 — 질문 이름별 답과 토큰 사용량. */
@JsonIgnoreProperties(ignoreUnknown = true)
record JevResponse(String model, Map<String, Answer> answers, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Answer(String choice, Double confidence, Map<String, Double> probabilities) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("input_tokens") Integer inputTokens) {
    }
}
