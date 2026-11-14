package com.momentory.retrospect.domain.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 규칙 층 안전 탐지 — 도메인 서비스 (원본 safety.py).
 *
 * <p>탐지 2층 중 '바닥·차단기' 역할. AI 판정과 별개로 항상 먼저 돈다.
 * 스프링 애노테이션이 없는 순수 자바다 — 빈 등록은 설정 계층이 한다.
 *
 * <p><b>주의</b>: 아래 키워드 목록은 원본 docstring 이 밝힌 대로 임상 검수 전 시드다.
 * 부분일치라 오탐이 있다. 「손목이 아파요」 같은 관용 표현 몇 가지만 {@link #IDIOMS} 로 좁게 뺐고,
 * 목록 자체의 교체는 임상 검수 후에 한다.
 */
public class SafetyPolicy {

    private static final List<String> IMMINENT =
            List.of("죽고 싶", "자살", "죽어버", "사라지고 싶", "목숨", "뛰어내", "손목");
    private static final List<String> RISK =
            List.of("살기 싫", "다 끝내", "의미 없", "없어지고 싶", "혼자 견디", "너무 힘들어서");
    private static final List<String> PROFANITY =
            List.of("씨발", "시발", "개새", "좆", "닥쳐");

    /**
     * 위험과 무관한 관용 표현 · 다른 대상 — 키워드를 보기 전에 공백 없앤 글에서 지운다. 일부러 좁게 둔다:
     * 「손목이 아프다」, 「손목터널」, 「목숨을 걸고 새로고침」 같은 꼴만이다. 과거 언급(「작년엔 죽고 싶었다」)이나
     * 문맥으로만 가를 수 있는 말(「사라지고 싶었다」, 「너무 힘들어서」)은 여기서 다루지 않는다.
     */
    private static final List<Pattern> IDIOMS = List.of(
            Pattern.compile("손목(이|도)?(너무|좀|계속|자꾸|많이|살짝|조금|약간)?(아프|아파|아팠|시큰|저리|저려|저렸)"),
            Pattern.compile("손목(터널|보호대|받침|시계|밴드|스트레칭|통증|염좌)"),
            Pattern.compile("목숨(을|까지)?걸(고|었|어)"));
    /** 같은 글에 번지점프 같은 활동이 있을 때만, 뛰어내리는 「순간」을 말한 꼴을 지운다(「뛰어내리고 싶다」는 남는다). */
    private static final Pattern JUMP_ACTIVITY = Pattern.compile("번지점프|스카이다이빙|다이빙대|패러글라이딩|낙하산");
    private static final Pattern JUMP_MOMENT = Pattern.compile("뛰어내(릴|렸을|리는|린|리기)(때|순간|직전|전)");
    /** 자해 · 죽음과 이어지는 말이 같은 글에 하나라도 있으면 관용 표현을 지우지 않는다(「손목이 아파도 긋고 싶다」). */
    private static final Pattern HARM = Pattern.compile("긋|그었|그어|칼|커터|면도|상처|피가|피를|자해|끊|죽|버리고싶|끝내");

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

    /** 스페이스뿐 아니라 줄바꿈 · 탭 · 전각 공백 · NBSP 까지 — 「죽고\n싶」처럼 끼워 넣어도 잡힌다. */
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    /**
     * 원본 {@code safety.scan()}. 공백을 모두 제거한 뒤 부분일치로 본다.
     * imminent 와 risk 는 배타(elif), profanity 는 별도 축이라 caution 으로 병합된다.
     */
    public ScanResult scan(String text) {
        String raw = withoutWhitespace(text == null ? "" : text);
        String t = withoutIdioms(raw);
        List<String> flags = new ArrayList<>();
        SafetyLevel level = SafetyLevel.NONE;

        if (containsAny(t, IMMINENT)) {
            level = SafetyLevel.IMMINENT;
            flags.add(FLAG_CRISIS);
        } else if (containsAny(t, RISK)) {
            level = SafetyLevel.RISK;
            flags.add(FLAG_CRISIS);
        }
        if (containsAny(raw, PROFANITY)) {
            flags.add(FLAG_PROFANITY);
            level = SafetyLevel.max(level, SafetyLevel.CAUTION);
        }
        return new ScanResult(level, flags);
    }

    private static String withoutIdioms(String spaceless) {
        if (HARM.matcher(spaceless).find()) {
            return spaceless;
        }
        String t = spaceless;
        for (Pattern idiom : IDIOMS) {
            t = idiom.matcher(t).replaceAll("·");
        }
        return JUMP_ACTIVITY.matcher(t).find() ? JUMP_MOMENT.matcher(t).replaceAll("·") : t;
    }

    /** 키워드도 공백을 제거해서 비교한다(사용자가 "죽 고 싶 다"처럼 띄어써도 잡힌다). */
    private static boolean containsAny(String spaceless, List<String> keywords) {
        for (String k : keywords) {
            if (spaceless.contains(withoutWhitespace(k))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutWhitespace(String text) {
        return WHITESPACE.matcher(text).replaceAll("");
    }

    /**
     * 이 글이 위기 안내 문구인가 — 회고 대화에 안내가 한 번이라도 나왔는지 볼 때 쓴다.
     * 「이어서 얘기하기」가 누적 안전 상태를 지우므로({@code SafetyState#reset}), 일기까지 간
     * 회고에서는 상태 대신 남은 안내 문구로 판정한다(바바의 편지 · 조용한 편지).
     */
    public boolean isGuidance(String text) {
        return text != null && GUIDANCE.values().stream().anyMatch(g -> text.startsWith(g.title()));
    }

    /** 도움받을 곳 목록 — 위기 안내와 조용한 편지가 같은 목록을 쓴다. */
    public List<String> resources() {
        return RESOURCES;
    }

    /** 해당 레벨의 고정 응답. none/caution 은 없다. */
    public Guidance guidanceFor(SafetyLevel level) {
        return GUIDANCE.get(level);
    }
}
