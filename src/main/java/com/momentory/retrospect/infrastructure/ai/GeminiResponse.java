package com.momentory.retrospect.infrastructure.ai;

import java.util.List;

/**
 * Gemini {@code generateContent} 응답의 필요한 부분만 담는다(나머지 필드는 무시).
 * 구조화 출력은 {@code candidates[0].content.parts[0].text} 에 JSON 문자열로 온다.
 */
record GeminiResponse(
        List<Candidate> candidates,
        UsageMetadata usageMetadata,
        String modelVersion) {

    record Candidate(Content content) {
    }

    record Content(List<Part> parts) {
    }

    record Part(String text) {
    }

    record UsageMetadata(
            Integer promptTokenCount,
            Integer candidatesTokenCount,
            Integer cachedContentTokenCount) {
    }

    /** 구조화 출력 JSON 텍스트 — 없으면 null. */
    String firstText() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Candidate candidate = candidates.get(0);
        if (candidate == null || candidate.content() == null
                || candidate.content().parts() == null || candidate.content().parts().isEmpty()) {
            return null;
        }
        return candidate.content().parts().get(0).text();
    }
}
