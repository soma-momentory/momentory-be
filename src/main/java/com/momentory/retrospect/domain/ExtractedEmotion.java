package com.momentory.retrospect.domain;

/**
 * 대화에서 추출한 감정 한 건 (채팅흐름_v2 {@code diary_chat.emotions[]}).
 *
 * <p>{@code raw} 는 사용자가 실제로 쓴 표현("무시당한 느낌"), {@code normalized} 는 고정 10종
 * {@link Emotion} 중 하나로 정규화한 값(아직 못 정했으면 {@code null}). {@code evidence} 에는
 * 근거가 된 사용자 문장을 그대로 담는다(추정이 아니라 실제 발화).
 *
 * <p>이 값을 만드는 주체는 {@link com.momentory.retrospect.domain.assistant.EmotionExtractor}
 * 하나다 — 지금은 LLM 구현이지만, 나중에 전용 감정 분류 모델로 공급자만 갈아끼울 수 있도록
 * 대화 엔진에서 감정 판단을 이 경계 뒤로 분리한다.
 */
public record ExtractedEmotion(
        String raw,
        Emotion normalized,
        String timing,
        String cause,
        String evidence) {

    public ExtractedEmotion {
        raw = strip(raw);
        timing = strip(timing);
        cause = strip(cause);
        evidence = strip(evidence);
    }

    /** 사용자 원문 표현만으로 만든다 — 정규화·부가 정보가 아직 없을 때. */
    public static ExtractedEmotion ofRaw(String raw) {
        return new ExtractedEmotion(raw, null, null, null, null);
    }

    /** 이름을 {@code has-}로 둔다 — {@code is-}면 Jackson 이 record 컴포넌트 {@code normalized} 와 충돌한다. */
    public boolean hasNormalized() {
        return normalized != null;
    }

    private static String strip(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
