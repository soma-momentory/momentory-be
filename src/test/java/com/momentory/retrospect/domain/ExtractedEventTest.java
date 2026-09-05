package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추출 사건 값 객체 — {@code evidence} 는 채점의 사건 정렬(IoU) 기준이라 집합으로 정규화한다
 * (모델 비교 계획 §3.1, §7.1).
 */
class ExtractedEventTest {

    @Test
    @DisplayName("발화 번호는 중복을 지우고 오름차순으로 정렬한다")
    void normalizesEvidence() {
        ExtractedEvent event = new ExtractedEvent(1, "발표", "발표에서 말이 막힘", List.of(4, 2, 2, 3));

        assertThat(event.evidence()).containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("유효하지 않은 발화 번호는 버리고, 근거가 없으면 빈 목록이 된다")
    void dropsInvalidEvidence() {
        assertThat(new ExtractedEvent(1, "발표", "발표에서 말이 막힘", Arrays.asList(0, null, -1)).evidence()).isEmpty();
        assertThat(new ExtractedEvent(1, "발표", "발표에서 말이 막힘", null).evidence()).isEmpty();
    }

    @Test
    @DisplayName("빈 요약은 null 로 둔다 — 상태가 요약 없는 사건을 걸러낼 수 있게")
    void blankSummaryBecomesNull() {
        assertThat(new ExtractedEvent(1, "   ", "   ", List.of(1)).summary()).isNull();
        assertThat(new ExtractedEvent(1, "발표", "  발표에서 말이 막힘 ", List.of(1)).summary())
                .isEqualTo("발표에서 말이 막힘");
    }
}
