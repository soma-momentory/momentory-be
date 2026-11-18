package com.momentory.letter.presentation;

import com.momentory.letter.application.LetterPictureView;

import io.swagger.v3.oas.annotations.media.Schema;

/** 그림일기 한 장 — 앱의 서버 호출 통로가 JSON 만 다뤄 그림을 base64 로 싣는다. */
public record LetterPictureResponse(
        @Schema(description = "그림 형식", example = "image/jpeg") String mimeType,
        @Schema(description = "그림 바이트의 base64", example = "/9j/4AAQSkZJRgABAQAAAQABAAD...") String image
) {

    static LetterPictureResponse from(LetterPictureView view) {
        return new LetterPictureResponse(view.mimeType(), java.util.Base64.getEncoder().encodeToString(view.image()));
    }
}
