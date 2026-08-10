package com.momentory.retrospect.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 감정 택소노미 9종 — 값 객체 (원본 emotions.py).
 *
 * <p>선언 순서는 원본 {@code EMOTIONS} 리스트 순서와 같아야 한다 — 클라이언트가 번호로 고를 때 쓰인다.
 */
public enum Emotion {

    ANXIOUS("anxious", "불안함", "불안"),
    DEPRESSED("depressed", "우울함", "우울"),
    ANGRY("angry", "화남/답답", "답답"),
    HAPPY("happy", "행복함", "행복"),
    STUCK("stuck", "막막함", "막막"),
    LETHARGIC("lethargic", "무기력", "무기력"),
    TIRED("tired", "피곤함", "피곤"),
    PROUD("proud", "뿌듯함", "뿌듯"),
    CALM("calm", "평온함", "평온");

    /** 강점 기반(strength_based) 신호로 쓰이는 긍정 감정. */
    public static final Set<Emotion> POSITIVE =
            Collections.unmodifiableSet(EnumSet.of(HAPPY, PROUD, CALM));

    private static final Map<String, Emotion> BY_KEY;

    static {
        Map<String, Emotion> m = new LinkedHashMap<>();
        for (Emotion e : values()) {
            m.put(e.key, e);
        }
        BY_KEY = Collections.unmodifiableMap(m);
    }

    private final String key;
    private final String label;
    private final String stem;

    Emotion(String key, String label, String stem) {
        this.key = key;
        this.label = label;
        this.stem = stem;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * '-하다'를 붙일 수 있는 어근 (예: 불안 → "불안했고", "불안하다").
     * 대화 스크립트가 감정을 문장에 자연스럽게 녹일 때 쓴다.
     */
    public String stem() {
        return stem;
    }

    public boolean isPositive() {
        return POSITIVE.contains(this);
    }

    public static Optional<Emotion> fromKey(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    public static boolean isValid(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** 감정 키 → 라벨. 알 수 없는 키는 그대로 돌려준다(원본 {@code emotions.label()} 동작). */
    public static String labelOf(String key) {
        Emotion e = key == null ? null : BY_KEY.get(key);
        return e != null ? e.label : key;
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(Emotion::key).toList();
    }
}
