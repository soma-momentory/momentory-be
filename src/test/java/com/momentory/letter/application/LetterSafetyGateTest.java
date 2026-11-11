package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.letter.application.RiskJudgment.Trajectory;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 생성 전 결정표 — 설계서 6.6절 T1~T20(T8b 포함)을 그대로 단언한다. 표에 없는 입력은 NONE, 판정은 사용 가능,
 * 궤적은 STABLE, past-only-policy 는 GENTLE 이다.
 */
class LetterSafetyGateTest {

    private static final SafetyLevel N = SafetyLevel.NONE;
    private static final SafetyLevel C = SafetyLevel.CAUTION;
    private static final SafetyLevel R = SafetyLevel.RISK;
    private static final SafetyLevel I = SafetyLevel.IMMINENT;

    @Test
    @DisplayName("T1 위기 안내 → QUIET(D1) · T2 안전 기록 → QUIET(D2)")
    void crisisAndRiskEvent() {
        assertDecision(new LetterSafetyGate.Input(true, false, N, N, judged(N, N, N), true), "QUIET", "D1");
        assertDecision(new LetterSafetyGate.Input(false, true, N, N, judged(N, N, N), true), "QUIET", "D2");
    }

    @Test
    @DisplayName("T3 규칙 IMMINENT 는 판정자가 과거 언급이라 해도 QUIET(D3)")
    void ruleImminentIsFloor() {
        assertDecision(input(I, N, judged(N, I, N), true), "QUIET", "D3");
    }

    @Test
    @DisplayName("T4 · T5 판정 없음 — 규칙 RISK 면 QUIET(D4a), 아니면 GENTLE(D4b)")
    void noJudgment() {
        assertDecision(input(R, N, Optional.empty(), false), "QUIET", "D4a");
        assertDecision(input(N, N, Optional.empty(), false), "GENTLE", "D4b");
    }

    @Test
    @DisplayName("T6 지난 일기 RISK + 불확실 + 실험 NORMAL → GENTLE(D4b)")
    void uncertainWithPastRisk() {
        LetterSafetyGate.Input in = input(N, R, Optional.of(new RiskJudgment(N, N, R, Trajectory.STABLE, true,
                List.of())), false);
        assertThat(LetterSafetyGate.decide(in, LetterPolicy.NORMAL))
                .isEqualTo(new LetterSafetyGate.Decision(LetterPolicy.GENTLE, "D4b"));
    }

    @Test
    @DisplayName("T7 판정 오늘 현재 RISK → QUIET(D5)")
    void currentRisk() {
        assertDecision(input(N, N, judged(R, R, N), true), "QUIET", "D5");
    }

    @Test
    @DisplayName("T8 모순(현재 RISK · 언급 NONE)이라 못 써도 현재 RISK 신호는 버리지 않음 → QUIET(D4a) · T8b 현재 CAUTION + 불확실 → GENTLE(D4b)")
    void unusableButCurrentRisk() {
        assertDecision(input(N, N, judged(R, N, N), false), "QUIET", "D4a");
        assertDecision(input(N, N, Optional.of(new RiskJudgment(C, C, N, Trajectory.STABLE, true, List.of())), false),
                "GENTLE", "D4b");
    }

    @Test
    @DisplayName("T9 근거가 원문에 없어 못 쓰는 판정 + 규칙 RISK → QUIET(D4a)")
    void ungroundedWithRuleRisk() {
        assertDecision(input(R, N, judged(N, R, N), false), "QUIET", "D4a");
    }

    @Test
    @DisplayName("T10 지난 RISK 뒤 오늘 CAUTION 으로 악화 → QUIET(D6)")
    void escalation() {
        assertDecision(input(N, N, judged(C, C, R, Trajectory.ESCALATION), true), "QUIET", "D6");
    }

    @Test
    @DisplayName("T11 · T12 오늘 글의 RISK 가 과거 언급 → GENTLE(D7), NORMAL 은 되지 않음")
    void pastMentionToday() {
        assertDecision(input(R, N, judged(N, R, N), true), "GENTLE", "D7");
        assertDecision(input(N, N, judged(N, R, N), true), "GENTLE", "D7");
    }

    @Test
    @DisplayName("T13 규칙 CAUTION(욕설) · T14 판정 CAUTION → GENTLE(D8)")
    void caution() {
        assertDecision(input(C, N, judged(N, N, N), true), "GENTLE", "D8");
        assertDecision(input(N, N, judged(C, C, N), true), "GENTLE", "D8");
    }

    @Test
    @DisplayName("T15 지난 일기만 RISK → past-only-policy(GENTLE, D9) · T16 실험 NORMAL → NORMAL(D9)")
    void pastOnly() {
        LetterSafetyGate.Input in = input(N, N, judged(N, N, R, Trajectory.RECOVERY), true);
        assertDecision(in, "GENTLE", "D9");
        assertThat(LetterSafetyGate.decide(in, LetterPolicy.NORMAL))
                .isEqualTo(new LetterSafetyGate.Decision(LetterPolicy.NORMAL, "D9"));
    }

    @Test
    @DisplayName("T17 규칙 지난 RISK + ESCALATION 이어도 오늘 NONE 이면 QUIET 아님 → GENTLE(D9)")
    void escalationWithoutTodaySignal() {
        assertDecision(input(N, R, judged(N, N, N, Trajectory.ESCALATION), true), "GENTLE", "D9");
    }

    @Test
    @DisplayName("T18 지난 CAUTION 만 · T19 모두 깨끗 → NORMAL(D10)")
    void normal() {
        assertDecision(input(N, N, judged(N, N, C), true), "NORMAL", "D10");
        assertDecision(input(N, N, judged(N, N, N), true), "NORMAL", "D10");
    }

    @Test
    @DisplayName("T20 운영 설정(experiment=false)에서 past-only-policy NORMAL 이면 기동을 막는다")
    void normalPastOnlyNeedsExperiment() {
        assertThatThrownBy(() -> new LetterGateProperties(LetterGateProperties.Mode.ON, false, LetterPolicy.NORMAL,
                null, null, 7, 400, Duration.ofSeconds(4))).isInstanceOf(IllegalStateException.class);
        assertThat(new LetterGateProperties(null, true, LetterPolicy.NORMAL, null, null, 0, 0, null).mode())
                .isEqualTo(LetterGateProperties.Mode.OFF);
    }

    @Test
    @DisplayName("비교 실험 스위치는 운영 설정(experiment=false)에서 쓰면 기동을 막는다")
    void ablationNeedsExperiment() {
        LetterGateProperties.Ablation b1 = new LetterGateProperties.Ablation(false, false, false, true);
        assertThatThrownBy(() -> new LetterGateProperties(LetterGateProperties.Mode.ON, false, LetterPolicy.GENTLE,
                null, null, 7, 400, Duration.ofSeconds(4), b1)).isInstanceOf(IllegalStateException.class);
        assertThat(new LetterGateProperties(LetterGateProperties.Mode.ON, true, LetterPolicy.GENTLE, null, null, 7,
                400, Duration.ofSeconds(4), b1).ablation().simplePolicy()).isTrue();
        assertThat(new LetterGateProperties(null, false, null, null, null, 0, 0, null).ablation())
                .isEqualTo(LetterGateProperties.Ablation.NONE);
    }

    @Test
    @DisplayName("판정이 무엇이든 정책은 B0(위기 안내 · 안전 기록)보다 덜 막지 않는다")
    void neverLooserThanCurrentSystem() {
        for (SafetyLevel rule : SafetyLevel.values()) {
            for (SafetyLevel current : SafetyLevel.values()) {
                LetterSafetyGate.Input in = new LetterSafetyGate.Input(true, false, rule, N,
                        judged(current, SafetyLevel.max(current, rule), N), true);
                assertThat(LetterSafetyGate.decide(in, LetterPolicy.GENTLE).policy()).isEqualTo(LetterPolicy.QUIET);
            }
        }
    }

    private static void assertDecision(LetterSafetyGate.Input in, String policy, String row) {
        assertThat(LetterSafetyGate.decide(in, LetterPolicy.GENTLE))
                .isEqualTo(new LetterSafetyGate.Decision(LetterPolicy.valueOf(policy), row));
    }

    private static LetterSafetyGate.Input input(SafetyLevel ruleToday, SafetyLevel ruleHistory,
            Optional<RiskJudgment> judgment, boolean usable) {
        return new LetterSafetyGate.Input(false, false, ruleToday, ruleHistory, judgment, usable);
    }

    private static Optional<RiskJudgment> judged(SafetyLevel current, SafetyLevel mentioned, SafetyLevel history) {
        return judged(current, mentioned, history, Trajectory.STABLE);
    }

    private static Optional<RiskJudgment> judged(SafetyLevel current, SafetyLevel mentioned, SafetyLevel history,
            Trajectory trajectory) {
        return Optional.of(new RiskJudgment(current, mentioned, history, trajectory, false, List.of()));
    }
}
