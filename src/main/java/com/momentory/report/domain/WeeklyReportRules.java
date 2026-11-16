package com.momentory.report.domain;

import java.util.List;
import java.util.Set;

import com.momentory.retrospect.domain.Emotion;

/**
 * 주간 리포트 v2 의 고정 문구와 검사 규칙 — AI 가 쓰지 않는 문장은 모두 여기 있다.
 * 평가 · 약속 · 빈 위로 · 지시를 걸러내는 기준은 사람이 정한 것이라 AI 에게 맡기지 않는다.
 */
public final class WeeklyReportRules {

    /** 이만큼 일기를 쓴 주만 안쪽 장이 열린다. 그보다 적은 주는 표지 한 장이다. */
    public static final int DETAILED_DIARY_COUNT = 3;
    public static final Set<Emotion> GOOD_FEELS = Emotion.POSITIVE;

    public static final int COVER_LINE_MAX_LENGTH = 40;
    public static final int REVIEW_MAX_LENGTH = 300;
    public static final int GOOD_WORDS_MAX_LENGTH = 120;
    public static final int OK_LINE_MAX_LENGTH = 40;
    public static final String OK_LINE_ENDING = "괜찮았어요";

    /** 문장 안쪽 줄의 짧고 긴 기준 — 너무 짧으면 마음이 안 보이고, 너무 길면 공책 두 줄을 넘는다. */
    public static final int QUOTE_MIN_LENGTH = 8;
    public static final int QUOTE_MAX_LENGTH = 60;
    public static final int WORDS_MAX = 3;
    public static final int TASKS_MAX = 2;
    public static final int EVENTS_PER_EMOTION_MAX = 2;
    /** 쌓인 쉼터 기록은 이만큼 답이 모여야 한 줄을 쓴다. */
    public static final int REST_SO_FAR_MIN = 5;

    public static final String QUIET_COVER_LINE = "이 주의 이야기를 남겨 줘서 고마워요";
    public static final String QUIET_REVIEW = "이 주에는 많이 힘든 날이 있었죠. 오늘은 아무것도 해내지 않아도 괜찮아요. "
            + "혼자 버겁게 느껴질 때는 언제든 전화해도 돼요.";

    /** 좋은 말에 쓰지 않는 말 — 평가 · 약속 · 빈 위로 · 지시(good-words-rules). */
    private static final List<String> BANNED_WORDS = List.of("대단", "강한 사람", "성장", "잘될 거", "곧 나아",
            "누구나 그래", "힘드셨겠", "힘들었겠", "해 보세요", "해보세요", "하세요", "%", "점수");

    private WeeklyReportRules() {
    }

    /** AI 가 실패했을 때의 표지 문장 — 세는 사실만 말한다. */
    public static String fallbackCoverLine(long diaryCount) {
        return "이 주에 일기를 " + diaryCount + "편 남겼어요";
    }

    public static boolean isGoodFeel(Emotion emotion) {
        return emotion != null && GOOD_FEELS.contains(emotion);
    }

    public static boolean hasBannedWord(String text) {
        return text != null && BANNED_WORDS.stream().anyMatch(text::contains);
    }

    /** 쓸 수 있으면 다듬어서, 비었거나 길거나 금지어가 있으면 null 로 돌려준다. */
    public static String checked(String text, int maxLength) {
        String value = text == null ? "" : text.strip();
        return value.isEmpty() || value.length() > maxLength || hasBannedWord(value) ? null : value;
    }

    /** 공통점 문장은 「…날 괜찮았어요」 틀을 지켜야 한다 — 어기면 화면이 기본 문장을 쓴다. */
    public static String checkedOkLine(String text) {
        String value = checked(text, OK_LINE_MAX_LENGTH);
        return value != null && value.replaceAll("[.!\\s]+$", "").endsWith(OK_LINE_ENDING) ? value : null;
    }
}
