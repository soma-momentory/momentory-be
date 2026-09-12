package com.momentory.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.report.domain.WeeklyWishes.NeedCount;
import com.momentory.report.domain.WeeklyWishes.PracticedWish;
import com.momentory.report.domain.WeeklyWishes.WishCard;

/** 「이번 주 바람카드」 집계 규칙 — 같은 바람은 합치고, 실천한 카드만 목록에 선다. */
class WeeklyWishesTest {

    @Test
    @DisplayName("같은 바람은 한 줄로 합치고 몇 번 나왔는지 센다")
    void repeatedNeedsAreMergedWithCount() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(
                card(List.of("휴식", "인정"), "일찍 자기", false),
                card(List.of("휴식"), "산책하기", true),
                card(List.of("연결"), null, false)));

        assertThat(wishes.needs()).containsExactly(
                new NeedCount("휴식", 2), new NeedCount("인정", 1), new NeedCount("연결", 1));
    }

    @Test
    @DisplayName("많이 나온 바람이 앞에 서고, 같은 횟수면 먼저 온 카드 순이다")
    void needsAreOrderedByCountThenArrival() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(
                card(List.of("인정"), null, false),
                card(List.of("연결"), null, false),
                card(List.of("휴식"), null, false),
                card(List.of("휴식"), null, false)));

        assertThat(wishes.needs()).extracting(NeedCount::word)
                .containsExactly("휴식", "인정", "연결");
    }

    @Test
    @DisplayName("한 카드가 같은 바람을 두 번 세지 않는다")
    void oneCardCountsEachNeedOnce() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(card(List.of("휴식", "휴식"), null, false)));

        assertThat(wishes.needs()).containsExactly(new NeedCount("휴식", 1));
    }

    @Test
    @DisplayName("빈 단어는 버린다 — 지어내지도, 빈 알약을 세우지도 않는다")
    void blankNeedsAreDropped() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(card(Arrays.asList(" ", "휴식 "), null, false)));

        assertThat(wishes.needs()).containsExactly(new NeedCount("휴식", 1));
    }

    @Test
    @DisplayName("실천 목록에는 '해봤어요'까지 간 카드만, 받은 순서 그대로 선다")
    void onlyDoneCardsArePracticed() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(
                card(List.of("휴식"), "산책하기", true),
                card(List.of("인정"), "먼저 말 걸기", false),
                card(List.of("연결"), "안부 묻기", true)));

        assertThat(wishes.practiced()).containsExactly(
                new PracticedWish("산책하기", List.of("휴식")),
                new PracticedWish("안부 묻기", List.of("연결")));
    }

    @Test
    @DisplayName("작은 행동을 정하지 않고 해본 카드는 행동이 비어 온다 — 목록에서 빠지지는 않는다")
    void doneCardWithoutActionKeepsItsPlace() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(
                card(List.of("휴식"), null, true),
                card(List.of("인정"), "  ", true)));

        assertThat(wishes.practiced()).containsExactly(
                new PracticedWish(null, List.of("휴식")),
                new PracticedWish(null, List.of("인정")));
    }

    @Test
    @DisplayName("바람 없이 만들어진 카드도 실천하면 목록에 선다 — 바람만 비어 있다")
    void doneCardWithoutNeedsStillCounts() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of(card(List.of(), "산책하기", true)));

        assertThat(wishes.needs()).isEmpty();
        assertThat(wishes.practiced()).containsExactly(new PracticedWish("산책하기", List.of()));
    }

    @Test
    @DisplayName("카드가 없는 주는 두 목록 다 빈다")
    void emptyWeekHasNothing() {
        WeeklyWishes wishes = WeeklyWishes.of(List.of());

        assertThat(wishes.needs()).isEmpty();
        assertThat(wishes.practiced()).isEmpty();
    }

    private static WishCard card(List<String> needWords, String action, boolean done) {
        return new WishCard(needWords, action, done);
    }
}
