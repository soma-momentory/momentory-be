package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 세션 누적 안전 상태 — 애그리거트 내부 엔티티 (원본 state.py {@code Safety}).
 *
 * <p>레벨은 한 번 올라가면 내려오지 않는다(단조 증가) — 그 불변식을 {@link #merge} 안에서 지킨다.
 * 클래스명이 {@code Safety} 가 아닌 것은 safety 패키지와의 혼동을 피하기 위해서다.
 */
public class SafetyState {

    private SafetyLevel level = SafetyLevel.NONE;
    private final List<String> flags = new ArrayList<>();
    private String lastFlaggedMsgId;

    public SafetyLevel level() {
        return level;
    }

    public List<String> flags() {
        return List.copyOf(flags);
    }

    public String lastFlaggedMsgId() {
        return lastFlaggedMsgId;
    }

    /**
     * 규칙 층/AI 층 판정을 누적 병합한다 (원본 {@code engine._merge_safety}).
     *
     * @return 레벨이 올라갔으면 true — 호출자가 이걸 보고 도메인 이벤트를 낸다
     */
    public boolean merge(SafetyLevel incoming, Collection<String> incomingFlags, String msgId) {
        SafetyLevel previous = this.level;
        SafetyLevel merged = SafetyLevel.max(previous, incoming);
        boolean hasFlags = incomingFlags != null && !incomingFlags.isEmpty();

        if (merged != previous || hasFlags) {
            this.lastFlaggedMsgId = msgId;
        }
        this.level = merged;
        if (hasFlags) {
            for (String f : incomingFlags) {
                if (!flags.contains(f)) {
                    flags.add(f);
                }
            }
        }
        return merged != previous;
    }
}
