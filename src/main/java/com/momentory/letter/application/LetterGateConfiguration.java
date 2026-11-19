package com.momentory.letter.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 편지 안전 게이트 · 그림일기 설정을 등록한다. */
@Configuration
@EnableConfigurationProperties({LetterGateProperties.class, LetterPictureProperties.class})
class LetterGateConfiguration {
}
