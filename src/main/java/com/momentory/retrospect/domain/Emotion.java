package com.momentory.retrospect.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 감정 택소노미 — 대표 감정 12종. 값 객체.
 *
 * <p>감정 라벨링 기준(대표 12종 · 세부 60개)의 대표 감정과 같은 체계다. 추출·화면·저장이 모두 이
 * 열두 가지로 정규화한다. 세부 감정은 값으로 두지 않는다 — 추출 프롬프트가 대표 감정을 고르는
 * 힌트로만 쓴다. 저장·전송은 {@link #key()}(문자열)로 하므로 클라이언트의 번호 규약과 이 선언
 * 순서는 독립이다.
 *
 * <p><b>옛 10종과의 호환.</b> 10종(행복함·우울함·화남·답답함·막막함·무기력·피곤함 …)으로 저장된
 * 값과 배포된 옛 앱이 보내는 키를 읽을 수 있도록 {@link #fromKey(String)}·{@link #fromLabel(String)}
 * 이 옛 이름을 새 감정으로 옮겨 받는다. 내보내는 값은 언제나 새 키다.
 */
public enum Emotion {

    JOY("joy", "기쁨", "행복"),
    CALM("calm", "평온", "평온"),
    PROUD("proud", "뿌듯함", "뿌듯"),
    AFFECTION("affection", "애정", "애틋"),
    SAD("sad", "슬픔", "속상"),
    ANXIOUS("anxious", "불안", "불안"),
    ANGRY("angry", "분노", "분"),
    DISAPPOINTED("disappointed", "실망", "실망"),
    ASHAMED("ashamed", "부끄러움", "민망"),
    CONFUSED("confused", "혼란", "심란"),
    SORRY("sorry", "미안함", "미안"),
    EXHAUSTED("exhausted", "지침", "피곤");

    /** 강점 기반(strength_based) 신호로 쓰이는 긍정 감정. */
    public static final Set<Emotion> POSITIVE =
            Collections.unmodifiableSet(EnumSet.of(JOY, CALM, PROUD, AFFECTION));

    private static final Map<String, Emotion> BY_KEY;
    private static final Map<String, Emotion> BY_LABEL;

    /** 옛 10종 키 → 새 감정. 키가 그대로인 넷(anxious·angry·proud·calm)은 여기 없다. */
    private static final Map<String, Emotion> LEGACY_BY_KEY = Map.of(
            "happy", JOY,
            "depressed", SAD,
            "frustrated", ANGRY,
            "stuck", CONFUSED,
            "lethargic", EXHAUSTED,
            "tired", EXHAUSTED);

    /** 옛 10종 라벨 → 새 감정. 진행 중이던 세션의 선택지가 옛 이름으로 돌아올 수 있다. */
    private static final Map<String, Emotion> LEGACY_BY_LABEL = Map.of(
            "행복함", JOY,
            "평온함", CALM,
            "불안함", ANXIOUS,
            "우울함", SAD,
            "화남", ANGRY,
            "답답함", ANGRY,
            "막막함", CONFUSED,
            "무기력", EXHAUSTED,
            "피곤함", EXHAUSTED);

    static {
        Map<String, Emotion> byKey = new LinkedHashMap<>();
        Map<String, Emotion> byLabel = new LinkedHashMap<>();
        for (Emotion e : values()) {
            byKey.put(e.key, e);
            byLabel.put(e.label, e);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        BY_LABEL = Collections.unmodifiableMap(byLabel);
    }

    private final String key;
    private final String label;
    private final String stem;

    Emotion(String key, String label, String stem) {
        this.key = key;
        this.label = label;
        this.stem = stem;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * '-하다'를 붙일 수 있는 어근 (예: 불안 → "불안했고", "불안하다").
     * 대화 스크립트가 감정을 문장에 자연스럽게 녹일 때 쓴다.
     *
     * <p>라벨에 '-하다'가 붙지 않는 감정(기쁨·슬픔·분노·지침 등)은 그 감정에 속하는 말 가운데
     * '-하다'가 붙는 것을 쓴다(기쁨 → "행복", 슬픔 → "속상", 지침 → "피곤"). 그래서 어근이 라벨과
     * 다를 수 있다 — 이름을 보여줄 때는 {@link #label()} 을 쓴다.
     */
    public String stem() {
        return stem;
    }

    public boolean isPositive() {
        return POSITIVE.contains(this);
    }

    /**
     * 키로 감정을 찾는다. 옛 10종 키({@code happy}·{@code depressed}·{@code frustrated}·
     * {@code stuck}·{@code lethargic}·{@code tired})도 새 감정으로 옮겨 받는다.
     */
    public static Optional<Emotion> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Emotion e = BY_KEY.get(key);
        return Optional.ofNullable(e != null ? e : LEGACY_BY_KEY.get(key));
    }

    /**
     * 저장된 값으로 감정을 찾는다 — 열거 상수 이름({@code JOY})과 키({@code joy})를 모두 받고,
     * 옛 10종의 이름·키도 새 감정으로 옮긴다. JSON 스냅샷처럼 이름으로 직렬화된 값을 읽을 때 쓴다.
     */
    public static Optional<Emotion> fromStored(String value) {
        return value == null ? Optional.empty()
                : fromKey(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * 라벨(예: "미안함")로 감정을 찾는다 — 선택지 라벨을 되돌려 받을 때 쓴다.
     * 옛 10종 라벨(예: "답답함")도 새 감정으로 옮겨 받는다.
     */
    public static Optional<Emotion> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String name = label.strip();
        Emotion e = BY_LABEL.get(name);
        return Optional.ofNullable(e != null ? e : LEGACY_BY_LABEL.get(name));
    }

    /** 지금 체계의 키인가 — 옛 키는 읽을 수는 있어도 유효한 키로 치지 않는다. */
    public static boolean isValid(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** 감정 키 → 라벨(옛 키도 새 라벨로). 알 수 없는 키는 그대로 돌려준다. */
    public static String labelOf(String key) {
        return fromKey(key).map(Emotion::label).orElse(key);
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(Emotion::key).toList();
    }
}
