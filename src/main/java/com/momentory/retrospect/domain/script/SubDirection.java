package com.momentory.retrospect.domain.script;

import java.util.Optional;

/** "다른 관점" 선택 뒤의 세부 방향 2택 (PDF "세부 방향 선택 UI"). */
public enum SubDirection {

    REFRAME("reframe", "그때 떠오른 걱정이나 판단이 정말 사실인지 살펴보고 싶어요",
            "인지 재구성형", RetroMode.REFRAME),
    STRENGTH("strength", "비슷한 상황에서 이전과 다르게 해낸 점을 찾아보고 싶어요",
            "강점 기반형", RetroMode.STRENGTH);

    private final String key;
    private final String label;
    private final String typeName;
    private final RetroMode mode;

    SubDirection(String key, String label, String typeName, RetroMode mode) {
        this.key = key;
        this.label = label;
        this.typeName = typeName;
        this.mode = mode;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String typeName() {
        return typeName;
    }

    public RetroMode mode() {
        return mode;
    }

    public static Optional<SubDirection> fromKey(String key) {
        for (SubDirection d : values()) {
            if (d.key.equals(key)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
