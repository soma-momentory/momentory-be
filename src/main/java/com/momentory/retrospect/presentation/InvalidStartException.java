package com.momentory.retrospect.presentation;

import com.momentory.retrospect.domain.Emotion;

/** 시작 요청 검증 실패 — 표현 계층이 400 으로 번역한다. */
public class InvalidStartException extends RuntimeException {

    InvalidStartException(String message) {
        super(message);
    }

    static InvalidStartException badEmotion(String field, String value) {
        return new InvalidStartException(
                "'%s' 필드 값 '%s' 은(는) 유효한 감정 키가 아닙니다. 9종 중 하나를 쓰세요: %s"
                        .formatted(field, value, String.join(", ", Emotion.keys())));
    }
}
