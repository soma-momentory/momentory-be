package com.momentory.retrospect.domain.safety;

import java.util.List;

/**
 * 안전 상황에서 내보내는 고정 응답 템플릿 — 값 객체 (원본 safety.py {@code Guidance}).
 * AI 생성이 아니라 상수다.
 */
public record Guidance(String title, String message, List<String> resources) {

    public Guidance {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /** 원본 {@code safety.render()}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n").append(message).append("\n");
        for (String r : resources) {
            sb.append("\n  · ").append(r);
        }
        return sb.toString();
    }
}
