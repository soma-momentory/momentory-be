package com.momentory.letter.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        String verifierModel, int pastDiaryDays, int pastDiaryChars, Duration openVerifyTimeout, Ablation ablation) {

    public enum Mode { OFF, SHADOW, ON }

    /**
     * 비교 실험용 스위치(설계서 8.2절) — 평가 하네스만 쓴다. 하나라도 기본값과 다르면 experiment=true 여야 한다.
     *
     * @param history      false 면 지난 일기를 보지 않는다(P − 지난 일기)
     * @param temporal     false 면 지난 일기를 날짜 없이 오늘 글 뒤에 붙이고 판정자는 등급 하나만 낸다(P − 시간 정보)
     * @param verify       false 면 생성 뒤 검증을 하지 않는다(P − 검증기)
     * @param simplePolicy true 면 결정표 대신 「RISK 이상 QUIET, CAUTION GENTLE, 그 밖 NORMAL」로 정한다. 오늘 글만
     *                     보는 등급 하나짜리 판정과 함께 쓰면 B1(단일 LLM 위험 판정)이다
     */
    public record Ablation(Boolean history, Boolean temporal, Boolean verify, Boolean simplePolicy) {

        public static final Ablation NONE = new Ablation(true, true, true, false);

        public Ablation {
            history = history == null || history;
            temporal = temporal == null || temporal;
            verify = verify == null || verify;
            simplePolicy = simplePolicy != null && simplePolicy;
        }
    }

    public LetterGateProperties(Mode mode, boolean experiment, LetterPolicy pastOnlyPolicy, String judgeModel,
            String verifierModel, int pastDiaryDays, int pastDiaryChars, Duration openVerifyTimeout) {
        this(mode, experiment, pastOnlyPolicy, judgeModel, verifierModel, pastDiaryDays, pastDiaryChars,
                openVerifyTimeout, null);
    }

    @ConstructorBinding // 아래 편의 생성자가 있어 스프링이 바인딩에 쓸 생성자를 정해 줘야 한다
    public LetterGateProperties {
        mode = mode == null ? Mode.OFF : mode;
        pastOnlyPolicy = pastOnlyPolicy == null ? LetterPolicy.GENTLE : pastOnlyPolicy;
        pastDiaryDays = pastDiaryDays <= 0 ? 7 : pastDiaryDays;
        pastDiaryChars = pastDiaryChars <= 0 ? 400 : pastDiaryChars;
        openVerifyTimeout = openVerifyTimeout == null ? Duration.ofSeconds(4) : openVerifyTimeout;
        ablation = ablation == null ? Ablation.NONE : ablation;
        if (!ablation.equals(Ablation.NONE) && !experiment) {
            throw new IllegalStateException("비교 실험 스위치(ablation)는 평가 실험(experiment=true)에서만 쓸 수 있습니다");
        }
        if (pastOnlyPolicy == LetterPolicy.NORMAL && !experiment) {
            throw new IllegalStateException(
                    "past-only-policy NORMAL 은 평가 실험(experiment=true)에서만 쓸 수 있습니다");
        }
    }
}
