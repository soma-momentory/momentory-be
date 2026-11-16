package com.momentory.retrospect.domain.judgment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 사용자 발화 하나에 대한 여섯 항목의 판단.
 *
 * @param model            실제로 답한 판단 모델 이름
 * @param questionsVersion 판단 질문과 선택지 정의의 버전
 * @param items            항목별 결과 — 여섯 항목이 모두 들어 있다
 * @param inputTokens      과금되는 입력 토큰 수
 * @param latencyMs        판단 호출에 걸린 시간
 */
public record TurnJudgment(String model, String questionsVersion,
        Map<JudgmentItem, ItemJudgment> items, int inputTokens, long latencyMs) {

    public TurnJudgment {
        if (items == null || !items.keySet().containsAll(EnumSet.allOf(JudgmentItem.class))) {
            throw new IllegalArgumentException("여섯 판단 항목이 모두 있어야 합니다.");
        }
        items = Collections.unmodifiableMap(new EnumMap<>(items));
    }

    public ItemJudgment of(JudgmentItem item) {
        return items.get(item);
    }
}
