package com.momentory.retrospect.domain.safety;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 안전 심각도 4단계 — 값 객체 (원본 safety.py {@code LEVELS}).
 *
 * <p>선언 순서 = 심각도 오름차순. {@link #atLeast}/{@link #max} 가 이 순서에 의존한다.
 */
public enum SafetyLevel {

    NONE("none"),
    CAUTION("caution"),
    RISK("risk"),
    IMMINENT("imminent");

    private static final Map<String, SafetyLevel> BY_KEY = new LinkedHashMap<>();

    static {
        for (SafetyLevel l : values()) {
            BY_KEY.put(l.key, l);
        }
    }

    private final String key;

    SafetyLevel(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 원본 {@code safety.max_level()}. */
    public static SafetyLevel max(SafetyLevel a, SafetyLevel b) {
        SafetyLevel x = a == null ? NONE : a;
        SafetyLevel y = b == null ? NONE : b;
        return x.ordinal() >= y.ordinal() ? x : y;
    }

    /** 원본 {@code safety.gte()}. */
    public boolean atLeast(SafetyLevel other) {
        return this.ordinal() >= (other == null ? NONE : other).ordinal();
    }

    /** CBT 를 중단해야 하는 수준인가. */
    public boolean stopsRetrospect() {
        return this == RISK || this == IMMINENT;
    }

    /** 알 수 없는 키는 {@link #NONE} (원본의 {@code _ORDER.get(a, 0)} 폴백과 같은 취급). */
    public static SafetyLevel fromKey(String key) {
        SafetyLevel l = key == null ? null : BY_KEY.get(key);
        return l != null ? l : NONE;
    }
}
