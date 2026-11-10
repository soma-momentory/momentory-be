package com.momentory.letter.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.momentory.letter.domain.LetterPolicy;

/**
 * 편지 안전 게이트 설정(설계서 6.9절). 꺼져 있으면(OFF) 지금 경로 그대로다.
 *
 * <p>설계서는 {@code enabled} · {@code shadow} 두 스위치였지만, 「켜졌는데 그림자」 같은 조합을 헷갈리지
 * 않게 {@code mode} 하나로 합쳤다. 지난 위험만 있는 날을 NORMAL 로 보내는 설정(D9)은 평가 하네스의
 * 실험에서만 허용한다 — 운영 설정으로 들어오면 기동을 막는다.
 *
 * @param mode             OFF(지금 그대로) · SHADOW(판정 · 검증을 돌려 기록만, 전달은 지금 그대로) · ON(적용)
 * @param pastOnlyPolicy   오늘은 깨끗하고 지난 일기에만 위험이 있을 때(D9)의 정책
 * @param judgeModel       판정자 모델 — 생성기와 다른 버전 고정 이름을 권한다
 * @param verifierModel    검증기 모델 — 생성기와 다른 모델
 * @param pastDiaryDays    판정자에만 넘기는 지난 일기의 날 수
 * @param pastDiaryChars   지난 일기 하루치를 이만큼만 넘긴다(데이터 최소화)
 * @param openVerifyTimeout 열 때 보충 경로의 검증 한도 — 넘기면 고정 기본 편지
 */
@ConfigurationProperties(prefix = "momentory.safety.letter-gate")
public record LetterGateProperties(Mode mode, boolean experiment, LetterPolicy pastOnlyPolicy, String judgeModel,
        String verifierModel, int pastDiaryDays, int pastDiaryChars, Duration openVerifyTimeout) {

    public enum Mode { OFF, SHADOW, ON }

    public LetterGateProperties {
        mode = mode == null ? Mode.OFF : mode;
        pastOnlyPolicy = pastOnlyPolicy == null ? LetterPolicy.GENTLE : pastOnlyPolicy;
        pastDiaryDays = pastDiaryDays <= 0 ? 7 : pastDiaryDays;
        pastDiaryChars = pastDiaryChars <= 0 ? 400 : pastDiaryChars;
        openVerifyTimeout = openVerifyTimeout == null ? Duration.ofSeconds(4) : openVerifyTimeout;
        if (pastOnlyPolicy == LetterPolicy.NORMAL && !experiment) {
            throw new IllegalStateException(
                    "past-only-policy NORMAL 은 평가 실험(experiment=true)에서만 쓸 수 있습니다");
        }
    }
}
