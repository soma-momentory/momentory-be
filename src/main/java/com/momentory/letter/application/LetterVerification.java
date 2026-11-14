package com.momentory.letter.application;

import java.util.List;
import java.util.Set;

/**
 * 생성 뒤 검증 결과(설계서 6.4 · 6.8절). 결과는 넷이다 — 통과, 가벼운 위반만, 주요 위반, 검증 불가.
 *
 * <p>검증 불가는 검증기 호출이 실패했거나, 검증기가 편지에 없는 문구를 위반이라고 지어낸 경우다. 검증되지 않은
 * 편지는 그대로 나가지 않는다.
 */
public record LetterVerification(Outcome outcome, List<Violation> violations) {

    public enum Outcome { PASS, MINOR, MAJOR, UNUSABLE }

    public enum Target { QUOTE, INSIGHT, TONIGHT_QUESTION, ACTION_TITLE }

    /** R1 위험 행동 조장 · R7 위기 신호 무시 — 게이트가 위험을 놓쳤다는 신호라 다시 쓰지 않고 조용한 편지로 닫는다. */
    private static final Set<String> QUIET_RULES = Set.of("R1", "R7");

    public record Violation(String rule, boolean major, Target target, String quote, String fix) {
    }

    public LetterVerification {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static LetterVerification unusable() {
        return new LetterVerification(Outcome.UNUSABLE, List.of());
    }

    public static LetterVerification of(List<Violation> violations) {
        Outcome outcome = violations.isEmpty() ? Outcome.PASS
                : violations.stream().anyMatch(Violation::major) ? Outcome.MAJOR : Outcome.MINOR;
        return new LetterVerification(outcome, violations);
    }

    public boolean closesQuiet() {
        return violations.stream().anyMatch(v -> v.major() && QUIET_RULES.contains(v.rule()));
    }

    /** 위반이 모두 작은 일 맞춤 제목에만 있다 — 정본 제목으로 바꾸면 닫힌다(다시 쓸 필요가 없다). */
    public boolean onlyActionTitles() {
        return !violations.isEmpty() && violations.stream().allMatch(v -> v.target() == Target.ACTION_TITLE);
    }

    /** 기록용 — 규칙 이름만(본문 · 근거 문구는 남기지 않는다). */
    public String rules() {
        return violations.stream().map(v -> v.rule() + (v.major() ? "!" : "")).distinct().sorted()
                .reduce((a, b) -> a + "," + b).orElse("-");
    }
}
