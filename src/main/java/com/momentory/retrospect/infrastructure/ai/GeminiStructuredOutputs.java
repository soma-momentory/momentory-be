package com.momentory.retrospect.infrastructure.ai;

import java.util.List;

/**
 * Gemini 구조화 출력 DTO들 — LLM JSON 을 그대로 받는 껍데기. 도메인 타입(enum 등)으로의 변환은
 * 어댑터가 맡는다(감정 키 → {@code Emotion}, 욕구 단어 → {@code Need} 검증 등).
 */
final class GeminiStructuredOutputs {

    private GeminiStructuredOutputs() {
    }

    /** 사건 한 건 — label 은 짧은 이름, evidence 는 그 사건에 속하는 사용자 발화 번호 전체. */
    record GeminiEvent(Integer id, String label, String summary, List<Integer> evidence) {
    }

    /**
     * 감정 한 건 — normalized 는 고정 10종 키 문자열, phase 는 before|during|after|now
     * (어댑터가 도메인 타입으로 매핑). evidence 는 근거 문장 원문, evidenceIds 는 그 발화 번호.
     */
    record GeminiEmotion(Integer eventId, String raw, String normalized, Integer intensity,
            String phase, String evidence, List<Integer> evidenceIds) {
    }

    /**
     * 추출 결과 — 사건(≤2)과 감정을 한 콜로 (모델 비교 계획 §3.1, §3.4).
     *
     * <p>{@code inferredEmotion} 은 <b>추출이 아니라 추론</b>이다 — emotions 가 비었을 때만 화면에
     * 보여줄 후보로 모델이 고른다. 채점 대상이 아니므로 emotions 와 섞지 않는다.
     */
    record GeminiExtraction(List<GeminiEvent> events, List<GeminiEmotion> emotions,
            String inferredEmotion) {
    }

    /** 바람(욕구) 후보 — 고정 목록에서 고른 단어들(어댑터가 Needs 로 검증). */
    record GeminiNeeds(List<String> words) {
    }

    /** 작은 행동 후보 — 문자열들. */
    record GeminiActions(List<String> actions) {
    }
}
