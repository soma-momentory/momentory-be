package com.momentory.report.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 「이번 주 바람카드」 — 그 주에 만든 바람 카드에서 <b>찾은 바람</b>과 <b>실천한 것</b>을 뽑는다.
 *
 * <p>{@link WeeklyMood} 와 나란한 자리다. 마음이 일기에서 오듯 바람은 바람 카드에서 오고, 둘 다
 * 리포트가 <b>세기만</b> 할 뿐 제 테이블을 갖지 않는다.
 *
 * <p>재료는 {@link WishCard} 목록이다 — 바람 카드 조회 결과에서 이 집계가 쓰는 셋(바람 단어 ·
 * 작은 행동 · 해봤는지)만 옮겨 담은 것으로, 부르는 쪽이 <b>최신순</b>으로 준다.
 *
 * <p>두 목록의 규칙:
 * <ul>
 *   <li>{@code needs} — 같은 바람 단어는 <b>한 줄로 합치고</b> 몇 번 나왔는지 센다. 많이 나온
 *       순이고, 같은 횟수끼리는 <b>먼저 온 카드</b>(최신) 순이다</li>
 *   <li>{@code practiced} — "해봤어요"까지 간 카드만, 받은 순서(최신) 그대로다</li>
 * </ul>
 *
 * <p><b>한 카드가 같은 바람을 두 번 세지 않는다.</b> 저장된 CSV 에 같은 단어가 두 번 들어 있어도
 * 그 카드에서는 한 번이다 — 한 장이 두 표를 던지면 그 카드만 무거워진다({@link WeeklyMood} 가
 * 하루의 감정을 세는 규칙과 같은 판단).
 */
public record WeeklyWishes(List<NeedCount> needs, List<PracticedWish> practiced) {

    public WeeklyWishes {
        needs = List.copyOf(Objects.requireNonNull(needs, "needs must not be null"));
        practiced = List.copyOf(Objects.requireNonNull(practiced, "practiced must not be null"));
    }

    /**
     * 집계 재료 한 장 — 바람 카드에서 이 집계가 쓰는 것만 옮겨 담는다.
     *
     * <p>{@code action} 은 작은 행동이다. 감정 탐색을 하다 "오늘은 여기까지"로 끝낸 카드는
     * 비어 있을 수 있다(그래도 "해봤어요"는 누를 수 있다).
     */
    public record WishCard(List<String> needWords, String action, boolean done) {

        public WishCard {
            needWords = needWords == null ? List.of() : List.copyOf(needWords);
        }
    }

    /** 그 주에 찾은 바람 하나 — 여러 카드에 나온 단어는 {@code count} 가 올라간다. */
    public record NeedCount(String word, long count) {
    }

    /** 실천한 바람 하나 — {@code action}(작은 행동)은 정하지 않고 해본 카드면 null 이다. */
    public record PracticedWish(String action, List<String> needs) {

        public PracticedWish {
            needs = needs == null ? List.of() : List.copyOf(needs);
        }
    }

    /** 그 주 카드들(최신순)에서 만든다. 카드가 없으면 두 목록 다 빈다. */
    public static WeeklyWishes of(List<WishCard> cards) {
        List<WishCard> all = cards == null ? List.of() : cards;

        // 먼저 온 것부터 담아 두고(LinkedHashMap) 횟수로만 다시 세운다 — 정렬이 안정적이라
        // 같은 횟수의 단어들은 담긴 순서, 곧 최신 카드 순으로 남는다.
        Map<String, Long> counts = new LinkedHashMap<>();
        for (WishCard card : all) {
            for (String word : distinctWords(card.needWords())) {
                counts.merge(word, 1L, Long::sum);
            }
        }
        List<NeedCount> needs = counts.entrySet().stream()
                .map(entry -> new NeedCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(NeedCount::count).reversed())
                .toList();

        List<PracticedWish> practiced = all.stream()
                .filter(WishCard::done)
                .map(card -> new PracticedWish(blankToNull(card.action()),
                        distinctWords(card.needWords())))
                .toList();

        return new WeeklyWishes(needs, practiced);
    }

    /** 공백을 털고 빈 단어를 버린 뒤 중복을 접는다 — 순서는 그대로 둔다. */
    private static List<String> distinctWords(List<String> words) {
        return words.stream()
                .map(String::strip)
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
    }

    /** 빈 문자열은 "안 정했다"와 같은 말이다 — null 로 눕혀 화면이 한 가지만 보게 한다. */
    private static String blankToNull(String action) {
        return action == null || action.isBlank() ? null : action.strip();
    }
}
