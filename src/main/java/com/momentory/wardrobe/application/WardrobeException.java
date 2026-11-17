package com.momentory.wardrobe.application;

public class WardrobeException extends RuntimeException {

    private final String code;

    private WardrobeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static WardrobeException itemNotFound() {
        return new WardrobeException("WARDROBE_ITEM_NOT_FOUND", "아이템을 찾을 수 없습니다.");
    }

    public static WardrobeException itemNotOwned() {
        return new WardrobeException("WARDROBE_ITEM_NOT_OWNED", "가지지 않은 아이템입니다.");
    }

    public static WardrobeException notEnoughTangerines() {
        return new WardrobeException("TANGERINE_NOT_ENOUGH", "귤이 모자랍니다.");
    }

    public String code() {
        return code;
    }
}
