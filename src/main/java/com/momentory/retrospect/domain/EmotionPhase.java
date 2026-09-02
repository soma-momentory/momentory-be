package com.momentory.retrospect.domain;

import java.util.Optional;

/**
 * 감정이 관찰된 시점 — 사건을 기준으로 언제의 감정인가 (모델 비교 계획 §3.1).
 *
 * <p>모델 비교에서 <b>Phase Macro F1</b> 의 채점 대상이다. 저장·전송은 {@link #key()} 문자열로 하고,
 * 스냅샷에는 열거 상수 이름으로 직렬화되므로 상수 이름을 바꾸면 기존 스냅샷과 호환이 깨진다.
 */
public enum EmotionPhase {

    /** 사건 전 — 기대·걱정처럼 일이 벌어지기 전의 감정. */
    BEFORE("before"),
    /** 사건 중 — 그 일이 벌어지는 동안의 감정. */
    DURING("during"),
    /** 사건 직후 — 끝나고 바로 든 감정. */
    AFTER("after"),
    /** 지금 — 대화 시점에 여전히 남아 있는 감정. */
    NOW("now");

    private final String key;

    EmotionPhase(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<EmotionPhase> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.strip().toLowerCase();
        for (EmotionPhase p : values()) {
            if (p.key.equals(normalized)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
