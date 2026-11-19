package com.momentory.letter.application;

/** 그림일기 한 장 — 화면용 그림이나 공유 · 저장용 카드. */
public record LetterPictureView(String mimeType, byte[] image) {

    public LetterPictureView {
        image = image.clone();
    }

    @Override
    public byte[] image() {
        return image.clone();
    }
}
