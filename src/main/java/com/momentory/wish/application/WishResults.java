package com.momentory.wish.application;

import com.momentory.wish.domain.WishOutcome;

import java.util.List;

/** 소원 서비스가 돌려주는 값들. */
public final class WishResults {

    private WishResults() {
    }

    public enum Author {
        USER,
        BABA
    }

    public record PondWish(long id, String content, int daysLeft, boolean light, boolean joined, Author author) {
    }

    public record Pond(boolean rulesAgreed, boolean postedToday, boolean hasFloatingWish, long floatingCount,
            List<PondWish> wishes) {
    }

    public record FloatingWish(long id, String content, int daysLeft, boolean joinedByOthers) {
    }

    public enum EndReason {
        EXPIRED,
        REPORTED
    }

    public record PastWish(long id, String content, EndReason endReason, WishOutcome outcome) {
    }

    public record MyWishes(List<FloatingWish> floating, List<PastWish> past) {
    }
}
