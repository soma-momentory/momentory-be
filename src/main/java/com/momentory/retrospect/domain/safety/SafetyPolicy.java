package com.momentory.retrospect.domain.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 규칙 층 안전 탐지 — 도메인 서비스 (원본 safety.py).
 *
 * <p>탐지 2층 중 '바닥·차단기' 역할. AI 판정과 별개로 항상 먼저 돈다.
 * 스프링 애노테이션이 없는 순수 자바다 — 빈 등록은 설정 계층이 한다.
 *
 * <p><b>주의</b>: 아래 키워드 목록은 원본 docstring 이 밝힌 대로 임상 검수 전 시드다.
 * 부분일치라 오탐이 있다(예: "손목이 아파요" → imminent). 원본 동작을 그대로 재현하며,
 * 목록 교체는 임상 검수 후에 한다.
 */
public class SafetyPolicy {

    private static final List<String> IMMINENT =
            List.of("죽고 싶", "자살", "죽어버", "사라지고 싶", "목숨", "뛰어내", "손목");
    private static final List<String> RISK =
            List.of("살기 싫", "다 끝내", "의미 없", "없어지고 싶", "혼자 견디", "너무 힘들어서");
    private static final List<String> PROFANITY =
            List.of("씨발", "시발", "개새", "좆", "닥쳐");

    public static final String FLAG_CRISIS = "crisis_expression";
    public static final String FLAG_PROFANITY = "profanity";

    private static final List<String> RESOURCES = List.of(
            "자살예방 상담전화 109 (24시간)",
            "정신건강 상담 1577-0199",
            "경찰 112 · 응급 119",
            "믿을 수 있는 가까운 사람에게 지금 마음을 전해보세요.");

    private static final Map<SafetyLevel, Guidance> GUIDANCE = Map.of(
            SafetyLevel.IMMINENT, new Guidance(
                    "지금 마음이 많이 힘드시죠",
                    "방금 하신 말씀이 마음에 깊이 남아요. 지금 느끼는 고통이 얼마나 큰지 짐작하기 어렵지만, "
                            + "혼자 감당하지 않으셨으면 해요. 잠시 회고를 멈추고, 아래 도움을 바로 받아보실 수 있어요.",
                    RESOURCES),
            SafetyLevel.RISK, new Guidance(
                    "잠깐 여기서 함께 멈춰볼게요",
                    "그 마음을 꺼내주셔서 고마워요. 지금은 생각을 정리하기보다, 그 힘든 마음 곁에 잠시 머무는 게 "
                            + "더 필요해 보여요. 원하시면 아래 도움도 언제든 곁에 있다는 걸 기억해 주세요.",
                    RESOURCES));

    /** 규칙 층 탐지 결과 — 값 객체. */
    public record ScanResult(SafetyLevel level, List<String> flags) {
        public ScanResult {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }

        public boolean isClean() {
            return level == SafetyLevel.NONE && flags.isEmpty();
        }
    }

    /**
     * 원본 {@code safety.scan()}. 공백을 모두 제거한 뒤 부분일치로 본다.
     * imminent 와 risk 는 배타(elif), profanity 는 별도 축이라 caution 으로 병합된다.
     */
    public ScanResult scan(String text) {
        String t = (text == null ? "" : text).replace(" ", "");
        List<String> flags = new ArrayList<>();
        SafetyLevel level = SafetyLevel.NONE;

        if (containsAny(t, IMMINENT)) {
            level = SafetyLevel.IMMINENT;
            flags.add(FLAG_CRISIS);
        } else if (containsAny(t, RISK)) {
            level = SafetyLevel.RISK;
            flags.add(FLAG_CRISIS);
        }
        if (containsAny(t, PROFANITY)) {
            flags.add(FLAG_PROFANITY);
            level = SafetyLevel.max(level, SafetyLevel.CAUTION);
        }
        return new ScanResult(level, flags);
    }

    /** 키워드도 공백을 제거해서 비교한다(사용자가 "죽 고 싶 다"처럼 띄어써도 잡힌다). */
    private static boolean containsAny(String spaceless, List<String> keywords) {
        for (String k : keywords) {
            if (spaceless.contains(k.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    /** 해당 레벨의 고정 응답. none/caution 은 없다. */
    public Guidance guidanceFor(SafetyLevel level) {
        return GUIDANCE.get(level);
    }
}
