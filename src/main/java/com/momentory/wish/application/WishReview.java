package com.momentory.wish.application;

/**
 * 심사 결과 — 통과와 고침일 때만 {@code content} 와 {@code reviewToken} 이 있고, 거절일 때만 {@code message} 가 있다.
 * 위기는 글을 저장하지 않고 도움받을 곳을 안내한다.
 */
public record WishReview(Result result, String content, String reviewToken, String message) {

    public enum Result {
        PASS,
        EDITED,
        REJECTED,
        CRISIS
    }

    static WishReview rejected(String message) {
        return new WishReview(Result.REJECTED, null, null, message);
    }

    static WishReview crisis() {
        return new WishReview(Result.CRISIS, null, null, null);
    }
}
