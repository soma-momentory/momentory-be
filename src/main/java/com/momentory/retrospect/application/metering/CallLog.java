package com.momentory.retrospect.application.metering;

/**
 * LLM 호출 한 건의 계측 기록 (원본 llm.py {@code CallLog}, 설계문서 §9).
 *
 * <p>베타 데이터로 "어떤 역할이 비용 1위인가", "질문 풀이 몇 %에서 AI 를 대체했나"를 보기 위한 것.
 *
 * @param role      AI-G1 | AI-G2 | AI-G3 | AI-G4 (원본의 역할 단위 파이프라인)
 * @param phase     이 호출이 일어난 회고 phase
 * @param poolUsed  AI 를 부르지 않고 질문 풀로 대체한 턴인가
 */
public record CallLog(
        String role,
        String model,
        String phase,
        int inTokens,
        int outTokens,
        int cachedTokens,
        long latencyMs,
        boolean poolUsed) {

    public boolean isCacheHit() {
        return cachedTokens > 0;
    }

    /** 질문 풀이 AI 를 대체한 턴. 토큰도 비용도 0이다. */
    public static CallLog poolSubstitution(String role, String phase) {
        return new CallLog(role, "-", phase, 0, 0, 0, 0L, true);
    }
}
