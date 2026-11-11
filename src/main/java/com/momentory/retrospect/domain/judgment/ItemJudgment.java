package com.momentory.retrospect.domain.judgment;

import java.util.Map;

/**
 * 판단 항목 하나의 결과.
 *
 * @param choice        확률이 가장 높은 선택지 키
 * @param confidence    선택지 확률이 얼마나 한쪽으로 모였는가(0~1). 정답을 보장하지 않는다.
 * @param probabilities 선택지 키별 확률
 */
public record ItemJudgment(String choice, double confidence, Map<String, Double> probabilities) {

    public ItemJudgment {
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
    }
}
