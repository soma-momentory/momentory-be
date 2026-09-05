package com.momentory.report.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.momentory.retrospect.domain.Emotion;

/**
 * 「이번 주의 마음」 — 하루 한 칸씩 일곱 칸과, 그 주를 한 줄로 요약하는 멘트.
 *
 * <p>멘트는 AI 를 쓰지 않고 아래 고정 규칙으로만 만든다:
 * <ul>
 *   <li>최다 횟수를 가진 감정이 하나뿐이면 그 감정에 정해둔 멘트를 그대로 쓴다
 *       ({@link #dominantMessageOf})</li>
 *   <li>최다 횟수를 여러 감정이 나눠 가지면 — "여러 마음이 번갈아 찾아온 한 주였어요."</li>
 *   <li>한 칸도 기록되지 않았으면 — "아직 이번 주에 기록된 마음이 없어요."</li>
 * </ul>
 *
 * <p>감정별 멘트는 기획이 정해준 문장 그대로다 — 어근을 조합해 만들지 않는다(우울함은 "가라앉은
 * 마음", 피곤함은 "피곤함"처럼 문장마다 표현이 다르다). {@code switch} 식으로 두어 감정이 늘면
 * 컴파일이 먼저 막는다.
 */
public record WeeklyMood(List<DailyMood> days, Emotion dominantEmotion, String message) {

    private static final String NO_RECORD_MESSAGE = "아직 이번 주에 기록된 마음이 없어요.";
    private static final String MIXED_MESSAGE = "여러 마음이 번갈아 찾아온 한 주였어요.";

    public WeeklyMood {
        days = List.copyOf(Objects.requireNonNull(days, "days must not be null"));
    }

    public static WeeklyMood of(List<DailyMood> days) {
        Emotion dominant = dominantOf(days);
        return new WeeklyMood(days, dominant, messageOf(days, dominant));
    }

    /**
     * 가장 많이 나온 감정 — 최다 횟수를 나눠 가진 감정이 둘 이상이거나 기록이 없으면 null.
     *
     * <p><b>하루의 감정을 전부 센다</b>(2026-09-05). 「늦게 일어나서 기분 안좋았는데 라면 먹고
     * 기분 좋아졌어」 같은 하루는 우울함과 행복함이 각각 한 번씩이다 — 대표 감정만 세던 동안
     * 뒤쪽 감정은 한 주 요약에 한 번도 잡히지 않았다.
     *
     * <p>그래서 <b>하루가 여러 표를 던진다</b>. 감정을 둘 남긴 날은 두 감정에 한 표씩 주고,
     * 하나만 남긴 날은 한 표만 준다. 같은 감정이 하루에 두 번 잡히지는 않는다(목록이 이미
     * 중복을 접었다) — 한 날이 같은 감정에 두 표를 던지면 그날만 무거워진다.
     */
    private static Emotion dominantOf(List<DailyMood> days) {
        Map<Emotion, Long> counts = days.stream()
                .flatMap(day -> day.emotions().stream())
                .collect(Collectors.groupingBy(emotion -> emotion, Collectors.counting()));
        if (counts.isEmpty()) {
            return null;
        }
        long max = Collections.max(counts.values());
        List<Emotion> top = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return top.size() == 1 ? top.getFirst() : null;
    }

    private static String messageOf(List<DailyMood> days, Emotion dominant) {
        if (days.stream().noneMatch(DailyMood::isRecorded)) {
            return NO_RECORD_MESSAGE;
        }
        return dominant == null ? MIXED_MESSAGE : dominantMessageOf(dominant);
    }

    /** 가장 많이 느낀 감정 하나에 붙는 멘트 — 감정마다 정해진 문장이 하나씩 있다. */
    private static String dominantMessageOf(Emotion dominant) {
        return switch (dominant) {
            case ANXIOUS -> "이번 주에는 불안한 마음을 가장 많이 느꼈어요. "
                    + "마음이 놓이지 않는 일이 있었는지 돌아봐도 좋아요.";
            case DEPRESSED -> "이번 주에는 가라앉은 마음을 가장 많이 느꼈어요. "
                    + "마음을 무겁게 만든 일이 무엇이었는지 천천히 살펴보세요.";
            case ANGRY -> "이번 주에는 화난 마음을 가장 많이 느꼈어요. "
                    + "원하는 대로 되지 않거나 참아야 했던 일이 있었을지도 몰라요.";
            case FRUSTRATED -> "이번 주에는 답답한 마음을 가장 많이 느꼈어요. "
                    + "뜻대로 풀리지 않아 갑갑했던 일이 있었을지도 몰라요.";
            case HAPPY -> "이번 주에는 행복한 마음을 가장 많이 느꼈어요. "
                    + "어떤 순간이 나를 기분 좋게 만드는지 기억해 두면 좋아요.";
            case STUCK -> "이번 주에는 막막한 마음을 가장 많이 느꼈어요. "
                    + "어디서부터 시작해야 할지 어려운 일이 있었을지도 몰라요.";
            case LETHARGIC -> "이번 주에는 무기력한 마음을 가장 많이 느꼈어요. "
                    + "지금은 의지보다 충분한 휴식이 필요한 때일 수도 있어요.";
            case TIRED -> "이번 주에는 피곤함을 가장 많이 느꼈어요. "
                    + "몸과 마음이 보내는 휴식 신호를 지나치고 있지는 않은지 살펴보세요.";
            case PROUD -> "이번 주에는 뿌듯한 마음을 가장 많이 느꼈어요. "
                    + "노력한 만큼 스스로의 변화를 발견한 한 주였을지도 몰라요.";
            case CALM -> "이번 주에는 평온한 마음을 가장 많이 느꼈어요. "
                    + "나를 편안하게 해준 환경이나 행동을 다음 주에도 이어가 보세요.";
        };
    }
}
