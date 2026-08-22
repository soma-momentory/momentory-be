package com.momentory.retrospect.infrastructure.ai;

import java.util.List;

/**
 * Gemini {@code generateContent} 응답의 필요한 부분만 담는다(나머지 필드는 무시).
 * 구조화 출력은 {@code candidates[0].content.parts[0].text} 에 JSON 문자열로 온다.
 */
record GeminiResponse(
        List<Candidate> candidates,
        PromptFeedback promptFeedback,
        UsageMetadata usageMetadata,
        String modelVersion) {

    record Candidate(Content content, String finishReason, List<SafetyRating> safetyRatings) {
    }

    record Content(List<Part> parts) {
    }

    record Part(String text) {
    }

    /** 프롬프트(입력) 자체가 막혔을 때 채워진다 — {@code blockReason} 있으면 후보가 없다. */
    record PromptFeedback(String blockReason, List<SafetyRating> safetyRatings) {
    }

    /**
     * 안전 등급 — 생성 호출 응답에 함께 온다(추가 호출 없음). {@code probability} 는
     * NEGLIGIBLE|LOW|MEDIUM|HIGH, {@code blocked} 는 이 범주가 응답을 실제로 막았는지다.
     */
    record SafetyRating(String category, String probability, Boolean blocked) {
    }

    record UsageMetadata(
            Integer promptTokenCount,
            Integer candidatesTokenCount,
            Integer cachedContentTokenCount) {
    }

    /**
     * Gemini 안전 필터가 응답을 <b>하드 차단</b>했는가 — 출력 재검열(추가 호출 0회). 프롬프트 차단·
     * {@code finishReason=SAFETY}·특정 범주 {@code blocked=true} 만 본다.
     *
     * <p><b>단순 HIGH 확률로는 막지 않는 이유.</b> 감정 회고에서는 슬픔·위기를 다루는 정상 응답도
     * DANGEROUS_CONTENT·자해 등급이 MEDIUM/HIGH 로 오른다. 확률로 막으면 핵심 기능이 깨지므로,
     * 모델이 실제로 텍스트를 주지 않은(=쓸 수 없는) 하드 차단만 폴백으로 떨어뜨린다.
     */
    boolean blockedBySafety() {
        if (promptFeedback != null && isPresent(promptFeedback.blockReason())) {
            return true;
        }
        if (candidates == null) {
            return false;
        }
        for (Candidate c : candidates) {
            if (c == null) {
                continue;
            }
            if ("SAFETY".equals(c.finishReason())) {
                return true;
            }
            if (c.safetyRatings() != null) {
                for (SafetyRating r : c.safetyRatings()) {
                    if (r != null && Boolean.TRUE.equals(r.blocked())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 로그용 차단 사유 — 관측성. */
    String safetyBlockReason() {
        if (promptFeedback != null && isPresent(promptFeedback.blockReason())) {
            return "prompt:" + promptFeedback.blockReason();
        }
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c == null) {
                    continue;
                }
                if ("SAFETY".equals(c.finishReason())) {
                    return "finishReason=SAFETY";
                }
                if (c.safetyRatings() != null) {
                    for (SafetyRating r : c.safetyRatings()) {
                        if (r != null && Boolean.TRUE.equals(r.blocked())) {
                            return "blocked:" + r.category();
                        }
                    }
                }
            }
        }
        return "unknown";
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
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
