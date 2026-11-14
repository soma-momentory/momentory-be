package com.momentory.letter.application;

import java.util.Optional;

import com.momentory.letter.domain.LetterPolicy;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 생성 전 결정표(설계서 6.3절) — 위에서부터 보고 처음 맞는 줄에서 멈춘다. 스프링 없는 순수 계산이라
 * 표 한 줄이 시험 사례 하나와 맞는다({@code LetterSafetyGateTest}).
 *
 * <p>지키는 원칙은 넷이다. 지금 운영 동작(D1)과 안전 기록(D2)은 그대로 QUIET 이다. 규칙 층 신호는 판정자가
 * 낮출 수 없다(D3 · D4a · D7). 지난 기록은 정책을 올리기만 하고(D6 · D9) 오늘 신호를 낮추지 않는다. 판정 칸 하나를
 * 더 위험하게 말하거나 판정을 덜 믿게 되어도 정책은 내려가지 않는다({@code LetterSafetyGateMonotonicityTest}).
 */
public final class LetterSafetyGate {

    private LetterSafetyGate() {
    }

    /**
     * @param crisisGuidance 이 일기를 만든 회고에 위기 안내가 나왔다
     * @param riskEvent      일기 날 이후 같은 사용자의 안전 기록이 있다
     * @param ruleToday      오늘 일기 · 회고 말의 규칙 검사 등급
     * @param ruleHistory    지난 일기 규칙 검사 등급의 최댓값
     * @param judgment       판정자 결과(호출하지 않았거나 실패면 empty)
     * @param usable         그 결과를 믿고 쓸 수 있는가({@link RiskJudgment#usable})
     */
    public record Input(boolean crisisGuidance, boolean riskEvent, SafetyLevel ruleToday, SafetyLevel ruleHistory,
            Optional<RiskJudgment> judgment, boolean usable) {
    }

    public record Decision(LetterPolicy policy, String row) {
    }

    public static Decision decide(Input in, LetterPolicy pastOnlyPolicy) {
        if (in.crisisGuidance()) {
            return quiet("D1");
        }
        if (in.riskEvent()) {
            return quiet("D2");
        }
        if (in.ruleToday() == SafetyLevel.IMMINENT) {
            return quiet("D3");
        }
        if (in.judgment().isEmpty()) {
            return noJudgment(in.ruleToday().atLeast(SafetyLevel.RISK));
        }
        RiskJudgment j = in.judgment().get();
        if (in.usable()) {
            return table(in, j, pastOnlyPolicy);
        }
        // 쓸 수 없는 판정(불확실 · 근거가 원문에 없음)은 위험 쪽으로만 쓴다 — 판정 없음(D4)보다 낮아지지 않는다.
        // 불확실하다고 했으면 지난 위험이 이어지는지 모른다는 뜻이라 악화(D6)를 배제하지 않는다.
        Decision floor = noJudgment(in.ruleToday().atLeast(SafetyLevel.RISK)
                || j.todayCurrent().atLeast(SafetyLevel.RISK));
        RiskJudgment raised = j.uncertain() ? j.withTrajectory(RiskJudgment.Trajectory.ESCALATION) : j;
        Decision t = table(in, raised, pastOnlyPolicy);
        return t.policy().ordinal() > floor.policy().ordinal() ? new Decision(t.policy(), "D4+" + t.row()) : floor;
    }

    private static Decision noJudgment(boolean risk) {
        return risk ? quiet("D4a") : new Decision(LetterPolicy.GENTLE, "D4b");
    }

    /** 판정을 쓰는 줄(D5~D10). 「지금 &gt; 언급」 모순은 판정을 버리지 않고 언급을 지금까지 올려 읽는다. */
    private static Decision table(Input in, RiskJudgment j, LetterPolicy pastOnlyPolicy) {
        SafetyLevel today = SafetyLevel.max(in.ruleToday(), j.todayCurrent());
        SafetyLevel mentioned = SafetyLevel.max(j.todayMentioned(), j.todayCurrent());
        SafetyLevel history = SafetyLevel.max(in.ruleHistory(), j.historyRisk());
        if (j.todayCurrent().atLeast(SafetyLevel.RISK)) {
            return quiet("D5");
        }
        if (j.trajectory() == RiskJudgment.Trajectory.ESCALATION && today.atLeast(SafetyLevel.CAUTION)
                && history.atLeast(SafetyLevel.RISK)) {
            return quiet("D6");
        }
        // 지난 위험이 있으면 D7 · D8 도 D9(past-only-policy)보다 약하지 않다 — 오늘 신호가 붙어 정책이 내려가지 않게.
        LetterPolicy gentle = history.atLeast(SafetyLevel.RISK) && pastOnlyPolicy.ordinal() > LetterPolicy.GENTLE.ordinal()
                ? pastOnlyPolicy : LetterPolicy.GENTLE;
        if (in.ruleToday() == SafetyLevel.RISK || mentioned.atLeast(SafetyLevel.RISK)) {
            return new Decision(gentle, "D7");
        }
        if (today == SafetyLevel.CAUTION) {
            return new Decision(gentle, "D8");
        }
        if (history.atLeast(SafetyLevel.RISK)) {
            return new Decision(pastOnlyPolicy, "D9");
        }
        return new Decision(LetterPolicy.NORMAL, "D10");
    }

    private static Decision quiet(String row) {
        return new Decision(LetterPolicy.QUIET, row);
    }
}
