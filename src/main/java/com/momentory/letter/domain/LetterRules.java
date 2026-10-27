package com.momentory.letter.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 편지에 들어가는 고정 문구와 검사 규칙 — AI 가 쓰지 않는 문장은 모두 여기 있다(PRD 5-4 초안).
 * 사람이 쓴 문장이 그대로 나가므로 문구를 바꿀 때는 이 파일만 고친다.
 */
public final class LetterRules {

    public static final int QUOTE_MAX_LENGTH = 40;
    /** AI 인용은 40자 안쪽을 부탁하지만, 조금 넘긴 원문까지 버리지는 않는다. */
    public static final int AI_QUOTE_MAX_LENGTH = 60;
    public static final int INSIGHT_MAX_LENGTH = 400;
    public static final int TONIGHT_QUESTION_MAX_LENGTH = 200;

    /** 기본 편지 본문 — 날짜로 하나를 고른다. */
    static final List<String> DEFAULT_BODIES = List.of(
            "어제 이야기를 남겨 줘서 고마워요. 이렇게 적어 둔 하루가 하나씩 쌓이고 있어요.",
            "어제 하루도 끝까지 돌아봐 줬네요. 오늘은 어떤 하루가 될지, 밤에 다시 들려줘요.",
            "어제 남긴 이야기, 잘 읽었어요. 오늘도 하루 끝에 같이 돌아봐요.");

    public static final String DEFAULT_TONIGHT_QUESTION = "오늘 밤엔 하루 중 가장 기억에 남는 일부터 물어볼게요.";

    private static final String QUIET_BODY = "어제는 많이 힘든 하루였죠. 오늘은 아무것도 하지 않아도 괜찮아요. "
            + "혼자 버겁게 느껴질 때는 언제든 이야기 나눌 수 있는 곳이 있어요.";

    /** 인용 대조에서 무시하는 글자 — 공백 · 문장부호 · 기호. */
    private static final Pattern IGNORED = Pattern.compile("[\\s\\p{P}\\p{S}]+");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。\\n]");
    private static final Pattern TONIGHT_FORM = Pattern.compile("^오늘 밤엔 (.{2,40})부터 물어볼게요\\.?$");
    private static final Pattern HOLLOW_TOPIC = Pattern.compile("^(무슨|어떤|어느)\\s*(이야기|얘기|일|것)$");
    private static final Pattern LEADING_TIME_WORD = Pattern.compile("^(어제|오늘|내일)\\s+");

    private LetterRules() {
    }

    public static String defaultBody(LocalDate letterDate) {
        return DEFAULT_BODIES.get(Math.floorMod(letterDate.toEpochDay(), DEFAULT_BODIES.size()));
    }

    /**
     * 조용한 편지 본문 — 도움받을 곳 목록은 회고의 위기 안내와 같은 것을 따로 붙인다. 화면이 이미
     * 「OO님에게,」를 붙이므로 닉네임을 넣지 않는다.
     */
    public static String quietBody() {
        return QUIET_BODY;
    }

    /**
     * 오늘 밤 첫 질문 예고가 「오늘 밤엔 ○○부터 물어볼게요.」 틀을 지켰는가. ○○가 「무슨 이야기」처럼
     * 비어 있으면 틀은 맞아도 버린다. 어기면 부르는 쪽이 기본 문장으로 바꾼다.
     */
    public static boolean isTonightForm(String question) {
        var m = TONIGHT_FORM.matcher(question == null ? "" : question.strip());
        return m.matches() && !HOLLOW_TOPIC.matcher(m.group(1)).matches();
    }

    /**
     * 인용 앞의 시점 말(어제 · 오늘 · 내일)을 뗀다 — 화면이 「어제 「…」라고 남겼죠」로 감싸므로 겹치거나
     * 어긋난다. 뗀 뒤에도 원문의 이어진 일부라 원문 대조는 그대로 통과한다.
     */
    public static String withoutLeadingTimeWord(String quote) {
        return quote == null ? null : LEADING_TIME_WORD.matcher(quote.strip()).replaceFirst("");
    }

    /** 기본 편지의 인용 — 일기의 첫 문장을 40자로 자른다. 원문 그대로라 지어낼 위험이 없다. */
    public static String fallbackQuote(String diary) {
        if (diary == null || diary.isBlank()) {
            return null;
        }
        String text = diary.strip();
        var end = SENTENCE_END.matcher(text);
        String first = end.find() ? text.substring(0, end.end()).strip() : text;
        first = withoutLeadingTimeWord(first);
        return first.length() <= QUOTE_MAX_LENGTH ? first : first.substring(0, QUOTE_MAX_LENGTH);
    }

    /**
     * 관점 앞의 「OO님,」을 뗀다 — 화면이 이미 「OO님에게,」를 붙이므로, 모델이 지시를 어기고 호칭으로
     * 시작하면 받는 사람이 두 번 보인다.
     */
    public static String withoutGreeting(String insight, String nickname) {
        String text = insight == null ? "" : insight.strip();
        if (nickname != null && !nickname.isBlank() && text.startsWith(nickname + "님,")) {
            return text.substring(nickname.length() + 2).strip();
        }
        return text;
    }

    /**
     * AI 가 준 인용이 정말 사용자의 글 안에 있는가 — 공백 · 문장부호만 다르면 같은 말로 본다.
     * 지어낸 인용은 편지 전체의 신뢰를 무너뜨리므로, 통과하지 못하면 그 줄을 뺀다.
     */
    public static boolean isVerbatim(String quote, Collection<String> sources) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        String needle = IGNORED.matcher(quote).replaceAll("");
        if (needle.isEmpty()) {
            return false;
        }
        return sources.stream().filter(s -> s != null)
                .anyMatch(s -> IGNORED.matcher(s).replaceAll("").contains(needle));
    }
}
