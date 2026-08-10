package com.momentory.retrospect.application.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UsageRecorderTest {

    private final UsageRecorder recorder =
            new UsageRecorder("gemini-flash-lite-latest", 0.10, 0.40);

    private void record(String sessionId, String role, int in, int out, int cached, long ms) {
        recorder.on(new LlmCallRecorded(sessionId,
                new CallLog(role, "gemini-flash-lite-latest", "common", in, out, cached, ms,
                        false)));
    }

    @Test
    @DisplayName("호출이 없으면 빈 요약")
    void emptyWhenNoCalls() {
        UsageSummary summary = recorder.summarize("unknown");

        assertThat(summary.paidCalls()).isZero();
        assertThat(summary.model()).isEqualTo("gemini-flash-lite-latest");
    }

    @Test
    @DisplayName("토큰과 지연이 합산된다")
    void aggregatesTokensAndLatency() {
        record("s1", "AI-G2", 1000, 200, 0, 500);
        record("s1", "AI-G2", 1200, 250, 800, 600);

        UsageSummary summary = recorder.summarize("s1");

        assertThat(summary.paidCalls()).isEqualTo(2);
        assertThat(summary.inTokens()).isEqualTo(2200);
        assertThat(summary.outTokens()).isEqualTo(450);
        assertThat(summary.cachedTokens()).isEqualTo(800);
        assertThat(summary.totalLatencyMs()).isEqualTo(1100);
    }

    @Test
    @DisplayName("역할별로 나눠 집계한다 — 어떤 역할이 비용 1위인지 보려는 것")
    void breaksDownByRole() {
        record("s1", "AI-G2", 1000, 200, 0, 500);
        record("s1", "AI-G2", 1000, 200, 0, 500);
        record("s1", "AI-G4", 3000, 900, 0, 1500);

        UsageSummary summary = recorder.summarize("s1");

        assertThat(summary.byRole()).containsOnlyKeys("AI-G2", "AI-G4");
        assertThat(summary.byRole().get("AI-G2").calls()).isEqualTo(2);
        assertThat(summary.byRole().get("AI-G2").inTokens()).isEqualTo(2000);
        assertThat(summary.byRole().get("AI-G4").calls()).isEqualTo(1);
        assertThat(summary.byRole().get("AI-G4").outTokens()).isEqualTo(900);
    }

    @Test
    @DisplayName("세션끼리 섞이지 않는다")
    void isolatesSessions() {
        record("s1", "AI-G2", 1000, 200, 0, 500);
        record("s2", "AI-G2", 9999, 999, 0, 999);

        assertThat(recorder.summarize("s1").inTokens()).isEqualTo(1000);
        assertThat(recorder.summarize("s2").inTokens()).isEqualTo(9999);
    }

    @Nested
    @DisplayName("질문 풀 대체")
    class PoolSubstitution {

        @Test
        @DisplayName("유료 호출과 따로 센다 — 토큰에도 비용에도 안 들어간다")
        void countedSeparately() {
            record("s1", "AI-G2", 1000, 200, 0, 500);
            recorder.recordPoolSubstitution("s1", "AI-G1", "opener");
            recorder.recordPoolSubstitution("s1", "AI-G2-fallback", "common");

            UsageSummary summary = recorder.summarize("s1");

            assertThat(summary.paidCalls()).isEqualTo(1);
            assertThat(summary.poolSubstitutions()).isEqualTo(2);
            assertThat(summary.inTokens()).isEqualTo(1000);
            assertThat(summary.byRole())
                    .as("풀 대체는 역할별 유료 집계에 끼면 안 된다")
                    .containsOnlyKeys("AI-G2");
        }

        @Test
        @DisplayName("대체 비율이 나온다 — 풀 확장 우선순위를 정하는 지표")
        void reportsRatio() {
            record("s1", "AI-G2", 100, 20, 0, 100);
            recorder.recordPoolSubstitution("s1", "AI-G1", "opener");
            recorder.recordPoolSubstitution("s1", "AI-G2-fallback", "common");
            recorder.recordPoolSubstitution("s1", "AI-G2-fallback", "type");

            assertThat(recorder.summarize("s1").poolSubstitutionRatio())
                    .isEqualTo(0.75, within(0.0001));
        }

        @Test
        @DisplayName("호출이 하나도 없으면 비율은 0 — 0으로 나누지 않는다")
        void ratioIsZeroWhenNothingHappened() {
            assertThat(recorder.summarize("s1").poolSubstitutionRatio()).isZero();
        }
    }

    @Test
    @DisplayName("대략 비용이 요율대로 계산된다")
    void estimatesCost() {
        record("s1", "AI-G2", 1_000_000, 1_000_000, 0, 100);

        // in 1M * $0.10 + out 1M * $0.40
        assertThat(recorder.summarize("s1").estimatedCostUsd()).isEqualTo(0.50, within(1e-9));
    }

    @Test
    @DisplayName("호출 흐름이 시간순으로 남는다 — 풀 대체도 흐름에 포함된다")
    void keepsChronologicalFlow() {
        recorder.recordPoolSubstitution("s1", "AI-G1", "opener");
        record("s1", "AI-G2", 100, 20, 0, 100);
        record("s1", "AI-G4", 300, 90, 0, 300);

        assertThat(recorder.summarize("s1").flow())
                .extracting(CallLog::role)
                .containsExactly("AI-G1", "AI-G2", "AI-G4");
    }

    @Test
    @DisplayName("세션을 잊으면 기록이 사라진다 — 인메모리 누수 방지")
    void forgetsSession() {
        record("s1", "AI-G2", 100, 20, 0, 100);
        recorder.forget("s1");

        assertThat(recorder.summarize("s1").paidCalls()).isZero();
    }
}
