package com.momentory.wish.application;

public class WishException extends RuntimeException {

    private final String code;

    private WishException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static WishException rulesRequired() {
        return new WishException("WISH_RULES_REQUIRED", "이용 규칙에 먼저 동의해주세요.");
    }

    public static WishException dailyLimit() {
        return new WishException("WISH_DAILY_LIMIT", "소원은 하루에 하나만 띄울 수 있습니다.");
    }

    public static WishException notFound() {
        return new WishException("WISH_NOT_FOUND", "소원을 찾을 수 없습니다.");
    }

    public static WishException reviewUnavailable() {
        return new WishException("WISH_REVIEW_UNAVAILABLE", "지금은 소원을 살펴볼 수 없습니다.");
    }

    public static WishException reviewInvalid() {
        return new WishException("WISH_REVIEW_INVALID", "소원을 다시 확인해주세요.");
    }

    public String code() {
        return code;
    }
}
