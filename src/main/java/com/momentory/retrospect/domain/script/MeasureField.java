package com.momentory.retrospect.domain.script;

/**
 * 측정 턴에 들어가는 슬라이더 한 개의 종류.
 *
 * <p>PDF 시나리오 기준 슬라이더는 최대 3개(생각 믿음 + 감정 2종)이고 전부 0~10 범위다.
 */
public enum MeasureField {

    /** 일정에 연결된 감정(예: 면접 스터디에서 느낀 불안)의 강도. */
    SCHEDULE_EMOTION("schedule_emotion"),
    /** 현재 감정(예: 지금 느끼는 우울)의 강도. */
    CURRENT_EMOTION("current_emotion"),
    /** 자동적 사고에 대한 믿음(인지 재구성형 전·후 측정). */
    BELIEF("belief");

    public static final int MIN = 0;
    public static final int MAX = 10;

    private final String key;

    MeasureField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static MeasureField fromKey(String key) {
        for (MeasureField f : values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }
}
