package com.momentory.letter.application;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 바바의 그림일기 설정. 기본은 꺼져 있다 — BE 를 먼저 배포하고 앱이 나간 뒤에 켠다.
 *
 * @param enabled    켜야 그림을 만든다. 꺼져 있으면 {@code hasPicture} 도 늘 거짓이다
 * @param userIds    비우면 모두, 채우면 이 사용자에게만 만든다(처음엔 팀 계정만 켠다)
 * @param planModel  1단계 장면 나누기 모델
 * @param imageModel 2단계 그리기 모델 — 가장 싼 이미지 모델을 쓴다(2026-11-18 결정)
 */
@ConfigurationProperties(prefix = "momentory.letter.picture")
public record LetterPictureProperties(boolean enabled, List<Long> userIds, String planModel, String imageModel) {

    public LetterPictureProperties {
        userIds = userIds == null ? List.of() : List.copyOf(userIds);
        planModel = planModel == null || planModel.isBlank() ? "gemini-3.8-flash" : planModel;
        imageModel = imageModel == null || imageModel.isBlank() ? "gemini-3.1-flash-lite-image" : imageModel;
    }

    /** 이 사용자의 편지에 그림을 만드는가. */
    public boolean allows(Long userId) {
        return enabled && (userIds.isEmpty() || userIds.contains(userId));
    }
}
