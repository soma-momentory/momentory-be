package com.momentory.retrospect.application.metering;

import java.util.List;
import java.util.Map;

/**
 * 한 세션의 LLM 사용량 집계 (원본 llm.py {@code UsageSummary}, 설계문서 §9).
 *
 * @param paidCalls         실제 AI 호출 수
 * @param poolSubstitutions 질문 풀이 AI 를 대체한 횟수 — 풀 확장 우선순위를 정하는 지표
 * @param flow              시간순 호출 흐름
 */
public record UsageSummary(
        String model,
        int paidCalls,
        int poolSubstitutions,
        int inTokens,
        int outTokens,
        int cachedTokens,
        long totalLatencyMs,
        Map<String, RoleUsage> byRole,
        double estimatedCostUsd,
        List<CallLog> flow) {

    /** 역할별 소계. */
    public record RoleUsage(int calls, int inTokens, int outTokens, int cachedTokens,
            long latencyMs) {
    }

    /** 질문 풀이 전체 턴 중 몇 %를 AI 없이 처리했는가. */
    public double poolSubstitutionRatio() {
        int total = paidCalls + poolSubstitutions;
        return total == 0 ? 0.0 : (double) poolSubstitutions / total;
    }

    public static UsageSummary empty(String model) {
        return new UsageSummary(model, 0, 0, 0, 0, 0, 0L, Map.of(), 0.0, List.of());
    }
}
