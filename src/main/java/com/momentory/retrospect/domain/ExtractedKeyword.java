package com.momentory.retrospect.domain;

/**
 * 대화에서 뽑은 키워드 한 건 (모델 비교 계획 §3.4) — 오늘을 관통하는 짧은 단어.
 *
 * <p>{@link ExtractedEvent} 와 달리 사건이 아니라 <b>누적·집계</b>의 단위다. 그래서 짧고 일반적인
 * 단어여야 날마다 같은 키워드가 같은 이름으로 쌓인다({@code retrospect_topics.label} 인덱스).
 *
 * @param eventId 그 키워드가 특정 사건에서 나온 것이면 그 사건 id — 감정을 그 사건에서 물려받는다.
 *                특정 사건에 매이지 않으면 {@code null}(세션 전체 감정을 물려받는다).
 */
public record ExtractedKeyword(String label, Integer eventId) {

    public ExtractedKeyword {
        label = label == null || label.isBlank() ? null : label.strip();
    }
}
