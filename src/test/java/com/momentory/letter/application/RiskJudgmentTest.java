package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.letter.application.RiskJudgment.Evidence;
import com.momentory.letter.application.RiskJudgment.Source;
import com.momentory.letter.application.RiskJudgment.Trajectory;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/** 「판정 사용 불가」의 정의(설계서 6.2절) — 쓸 수 없는 판정은 결정표의 D4 줄로 간다. */
class RiskJudgmentTest {

    private static final List<String> TODAY = List.of("오늘은 그냥 다 내려놓고 싶었다.", "예전엔 살기 싫었는데 요즘은 괜찮아요");
    private static final List<String> PAST = List.of("다 끝내고 싶다는 생각뿐이다");

    @Test
    void groundedJudgmentIsUsable() {
        RiskJudgment j = new RiskJudgment(SafetyLevel.NONE, SafetyLevel.RISK, SafetyLevel.RISK, Trajectory.RECOVERY,
                false, List.of(new Evidence("todayMentioned", Source.TODAY, "예전엔 살기 싫었는데"),
                        new Evidence("historyRisk", Source.PAST, "다 끝내고 싶다는 생각뿐")));
        assertThat(j.usable(TODAY, PAST)).isTrue();
    }

    @Test
    void nonVerbatimEvidenceIsUnusable() {
        RiskJudgment j = new RiskJudgment(SafetyLevel.NONE, SafetyLevel.RISK, SafetyLevel.NONE, Trajectory.STABLE,
                false, List.of(new Evidence("todayMentioned", Source.TODAY, "죽고 싶었다")));
        assertThat(j.usable(TODAY, PAST)).isFalse();
    }

    @Test
    void evidenceFromWrongSectionIsUnusable() {
        // 지난 일기의 문장을 오늘 칸의 근거로 대면 쓰지 않는다 — 시점을 섞은 판정이다.
        RiskJudgment j = new RiskJudgment(SafetyLevel.RISK, SafetyLevel.RISK, SafetyLevel.NONE, Trajectory.STABLE,
                false, List.of(new Evidence("todayCurrent", Source.TODAY, "다 끝내고 싶다는 생각뿐"),
                        new Evidence("todayMentioned", Source.TODAY, "다 끝내고 싶다는 생각뿐")));
        assertThat(j.usable(TODAY, PAST)).isFalse();
    }

    @Test
    void currentAboveMentionedIsStillUsable() {
        // 「지금 > 언급」은 버리지 않는다 — 결정표가 언급을 지금까지 올려 읽는다(버리면 위험을 더 말한 판정이 정책을 낮춘다).
        RiskJudgment j = new RiskJudgment(SafetyLevel.RISK, SafetyLevel.NONE, SafetyLevel.NONE, Trajectory.STABLE,
                false, List.of(new Evidence("todayCurrent", Source.TODAY, "다 내려놓고 싶었다")));
        assertThat(j.usable(TODAY, PAST)).isTrue();
    }

    @Test
    void uncertainIsUnusable() {
        assertThat(new RiskJudgment(SafetyLevel.NONE, SafetyLevel.NONE, SafetyLevel.NONE, Trajectory.UNKNOWN, true,
                List.of()).usable(TODAY, PAST)).isFalse();
    }
}
