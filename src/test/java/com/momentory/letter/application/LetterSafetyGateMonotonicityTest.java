package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.momentory.letter.application.RiskJudgment.Trajectory;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 결정표 전수 검사 — 입력 조합(위기 안내 · 안전 기록 · 규칙 두 칸 · 판정 세 칸 · 흐름 · 불확실 · 근거)을 모두 돌려
 * 안전 성질을 단언한다. 같은 검사의 파이썬 사본이 분석 폴더의 gate_monotonicity_check_v2.py 다.
 */
class LetterSafetyGateMonotonicityTest {

    private static final SafetyLevel[] L = SafetyLevel.values();
    private static final String TODAY = "오늘 쓴 글";
    private static final String PAST = "지난 일기";

    record Case(boolean crisis, boolean event, SafetyLevel ruleToday, SafetyLevel ruleHistory, SafetyLevel current,
            SafetyLevel mentioned, SafetyLevel history, Trajectory trajectory, boolean uncertain, boolean grounded) {

        /** 근거가 있으면 칸마다 원문 그대로인 근거를 붙이고, 쓸 수 있는지는 실제 {@link RiskJudgment#usable} 로 정한다. */
        RiskJudgment judgment() {
            List<RiskJudgment.Evidence> evidence = grounded ? List.of(
                    new RiskJudgment.Evidence("todayCurrent", RiskJudgment.Source.TODAY, TODAY),
                    new RiskJudgment.Evidence("todayMentioned", RiskJudgment.Source.TODAY, TODAY),
                    new RiskJudgment.Evidence("historyRisk", RiskJudgment.Source.PAST, PAST)) : List.of();
            return new RiskJudgment(current, mentioned, history, trajectory, uncertain, evidence);
        }

        boolean usable() {
            return judgment().usable(List.of(TODAY), List.of(PAST));
        }

        LetterPolicy policy(LetterPolicy pastOnly) {
            return LetterSafetyGate.decide(new LetterSafetyGate.Input(crisis, event, ruleToday, ruleHistory,
                    Optional.of(judgment()), usable()), pastOnly).policy();
        }

        LetterPolicy withoutJudgment(LetterPolicy pastOnly) {
            return LetterSafetyGate.decide(new LetterSafetyGate.Input(crisis, event, ruleToday, ruleHistory,
                    Optional.empty(), false), pastOnly).policy();
        }

        Case raise(int field) {
            return new Case(crisis, event, ruleToday, ruleHistory, field == 0 ? up(current) : current,
                    field == 1 ? up(mentioned) : mentioned, field == 2 ? up(history) : history, trajectory, uncertain,
                    grounded);
        }

        /** F1 — 불확실해도 판정 세 칸 · 규칙 두 칸이 모두 NONE 이면 일반 편지라 판정 없음(GENTLE)보다 낮다(의도). */
        boolean signalFree() {
            return uncertain && ruleToday == SafetyLevel.NONE && ruleHistory == SafetyLevel.NONE
                    && current == SafetyLevel.NONE && mentioned == SafetyLevel.NONE && history == SafetyLevel.NONE;
        }

        Case trust(boolean u, boolean g) {
            return new Case(crisis, event, ruleToday, ruleHistory, current, mentioned, history, trajectory, u, g);
        }
    }

    @ParameterizedTest
    @EnumSource(LetterPolicy.class)
    void exhaustive(LetterPolicy pastOnly) {
        List<String> weakerThanB0 = new ArrayList<>();
        List<String> belowRuleFloor = new ArrayList<>();
        List<String> raisedButLowered = new ArrayList<>();
        List<String> lessTrustedButLowered = new ArrayList<>();
        List<String> loweredByUntrusted = new ArrayList<>();
        for (Case c : cases()) {
            LetterPolicy p = c.policy(pastOnly);
            LetterPolicy b0 = c.crisis() || c.event() ? LetterPolicy.QUIET : LetterPolicy.NORMAL;
            if (p.ordinal() < b0.ordinal()) {
                weakerThanB0.add(c.toString());
            }
            if (p.ordinal() < ruleFloor(c.ruleToday()).ordinal()) {
                belowRuleFloor.add(c.toString());
            }
            for (int f = 0; f < 3; f++) {
                Case up = c.raise(f);
                if (up.policy(pastOnly).ordinal() < p.ordinal()) {
                    raisedButLowered.add(c + " 칸 " + f);
                }
            }
            if (c.usable()) {
                for (Case less : List.of(c.trust(true, true), c.trust(false, false), c.trust(true, false))) {
                    if (!less.usable() && less.policy(pastOnly).ordinal() < p.ordinal()) {
                        lessTrustedButLowered.add(c.toString());
                    }
                }
            } else if (!c.signalFree() && p.ordinal() < c.withoutJudgment(pastOnly).ordinal()) {
                loweredByUntrusted.add(c.toString());
            }
        }
        assertThat(weakerThanB0).as("B0 보다 약함").isEmpty();
        assertThat(belowRuleFloor).as("규칙 바닥보다 약함").isEmpty();
        assertThat(raisedButLowered).as("판정 칸을 올렸는데 정책이 내려감").isEmpty();
        assertThat(lessTrustedButLowered).as("판정을 덜 믿게 됐는데 정책이 내려감").isEmpty();
        assertThat(loweredByUntrusted).as("쓸 수 없는 판정이 판정 없음보다 정책을 낮춤").isEmpty();
    }

    private static List<Case> cases() {
        List<Case> all = new ArrayList<>();
        for (boolean crisis : new boolean[] {false, true}) {
            for (boolean event : new boolean[] {false, true}) {
                for (SafetyLevel rt : L) {
                    for (SafetyLevel rh : L) {
                        for (SafetyLevel cur : L) {
                            for (SafetyLevel men : L) {
                                for (SafetyLevel his : L) {
                                    for (Trajectory t : Trajectory.values()) {
                                        for (boolean u : new boolean[] {false, true}) {
                                            for (boolean g : new boolean[] {false, true}) {
                                                all.add(new Case(crisis, event, rt, rh, cur, men, his, t, u, g));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return all;
    }

    /** 규칙 바닥 — RISK 면 적어도 GENTLE, IMMINENT 면 QUIET. CAUTION(욕설 목록)은 판정이 있으면 바닥이 아니다(F2). */
    private static LetterPolicy ruleFloor(SafetyLevel rule) {
        return rule == SafetyLevel.IMMINENT ? LetterPolicy.QUIET
                : rule == SafetyLevel.RISK ? LetterPolicy.GENTLE : LetterPolicy.NORMAL;
    }

    private static SafetyLevel up(SafetyLevel s) {
        return s.ordinal() + 1 < L.length ? L[s.ordinal() + 1] : s;
    }
}
