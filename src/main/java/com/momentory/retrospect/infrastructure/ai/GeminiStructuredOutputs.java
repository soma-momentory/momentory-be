package com.momentory.retrospect.infrastructure.ai;

import java.util.List;

/**
 * Gemini 구조화 출력 DTO들 — LLM JSON 을 그대로 받는 껍데기. 도메인 타입(enum 등)으로의 변환은
 * 어댑터가 맡는다(감정 키 → {@code Emotion}, 욕구 단어 → {@code Need} 검증 등).
 */
final class GeminiStructuredOutputs {

    private GeminiStructuredOutputs() {
    }

    /** 감정 추출 결과 — normalized 는 고정 10종 키 문자열(어댑터가 Emotion 으로 매핑). */
    record GeminiEmotion(String raw, String normalized, String timing, String cause,
            String evidence) {
    }

    record GeminiEmotions(List<GeminiEmotion> emotions) {
    }

    /** 바람(욕구) 후보 — 고정 목록에서 고른 단어들(어댑터가 Needs 로 검증). */
    record GeminiNeeds(List<String> words) {
    }

    /** 작은 행동 후보 — 문자열들. */
    record GeminiActions(List<String> actions) {
    }
}
