package com.momentory.letter.application;

import java.util.Collection;
import java.util.List;

import com.momentory.letter.domain.LetterRules;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 생성 전 판정자의 결과 — 오늘 글과 지난 기록의 위험을 칸을 나눠 받는다(설계서 5.3절).
 *
 * @param todayCurrent   오늘 글에서 지금도 이어지는 위험
 * @param todayMentioned 오늘 글에 나온 위험 표현의 가장 높은 등급(「예전에 죽고 싶었는데」 같은 과거 언급 포함)
 * @param historyRisk    지난 일기에만 있는 위험
 * @param evidence       등급마다 근거 문구(원문 그대로)
 */
public record RiskJudgment(SafetyLevel todayCurrent, SafetyLevel todayMentioned, SafetyLevel historyRisk,
        Trajectory trajectory, boolean uncertain, List<Evidence> evidence) {

    public enum Trajectory { STABLE, SWITCH, ESCALATION, RECOVERY, UNKNOWN }

    public enum Source { TODAY, PAST }

    public record Evidence(String field, Source source, String quote) {
    }

    public RiskJudgment {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * 결정표가 믿고 써도 되는가(설계서 6.2절). 불확실하다고 했거나, 지금 이어지는 위험이 오늘 글의 위험 언급보다
     * 높거나(모순), NONE 보다 높은 칸에 원문 그대로인 근거가 없으면 쓰지 않는다. 쓸 수 없는 판정은 결정표의
     * D4 줄로 가서 NORMAL 이 되지 않는다.
     */
    public boolean usable(Collection<String> todaySources, Collection<String> pastSources) {
        if (uncertain || todayCurrent.ordinal() > todayMentioned.ordinal()) {
            return false;
        }
        return grounded("todayCurrent", todayCurrent, todaySources)
                && grounded("todayMentioned", todayMentioned, todaySources)
                && grounded("historyRisk", historyRisk, pastSources);
    }

    private boolean grounded(String field, SafetyLevel level, Collection<String> sources) {
        return level == SafetyLevel.NONE || evidence.stream()
                .anyMatch(e -> field.equals(e.field()) && LetterRules.isVerbatim(e.quote(), sources));
    }
}
