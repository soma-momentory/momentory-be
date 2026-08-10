package com.momentory.retrospect.domain.script;

import java.util.List;

/**
 * 모드 스크립트의 한 턴 정의 — 값 객체.
 *
 * <p>흐름(다음에 뭘 묻는가)은 이 정의가 결정하고, AI는 문구만 만든다.
 * AI가 실패하면 {@code fallbackText}/{@code fallbackOptions}(PDF 시나리오의 원형 문구)로 내려간다.
 * 폴백 텍스트의 토큰({@code {일정}}·{@code {감정1}}·{@code {감정2}}·{@code {생각}})은
 * {@link Scripts#fill}이 채운다.
 *
 * @param id             답변 저장 키 (예: automatic_thought)
 * @param kind           턴 형태
 * @param intent         AI에게 주는 이 턴의 의도 설명 (TEXT/CHOICE 전용)
 * @param optionCount    CHOICE 턴의 보기 개수
 * @param describedOptions 보기에 한 줄 설명이 붙는가 (행동 선택지)
 * @param actionStep     이 턴의 선택이 '행동 카드'의 목표 행동이 되는가
 * @param measures       MEASURE 턴의 슬라이더 구성
 */
public record ScriptStep(
        String id,
        StepKind kind,
        String intent,
        int optionCount,
        boolean describedOptions,
        boolean actionStep,
        List<MeasureField> measures,
        String fallbackText,
        List<OptionItem> fallbackOptions) {

    public ScriptStep {
        measures = measures == null ? List.of() : List.copyOf(measures);
        fallbackOptions = fallbackOptions == null ? List.of() : List.copyOf(fallbackOptions);
    }

    static ScriptStep text(String id, String intent, String fallbackText) {
        return new ScriptStep(id, StepKind.TEXT, intent, 0, false, false, List.of(),
                fallbackText, List.of());
    }

    static ScriptStep choice(String id, String intent, String fallbackText,
            List<OptionItem> fallbackOptions) {
        return new ScriptStep(id, StepKind.CHOICE, intent, fallbackOptions.size(), false, false,
                List.of(), fallbackText, fallbackOptions);
    }

    /** 행동 선택 턴 — 보기 2개, 제목+한 줄 설명, 선택이 행동 카드가 된다. */
    static ScriptStep action(String id, String intent, String fallbackText,
            List<OptionItem> fallbackOptions) {
        return new ScriptStep(id, StepKind.CHOICE, intent, fallbackOptions.size(), true, true,
                List.of(), fallbackText, fallbackOptions);
    }

    static ScriptStep measure(String id, String fallbackText, MeasureField... fields) {
        return new ScriptStep(id, StepKind.MEASURE, null, 0, false, false, List.of(fields),
                fallbackText, List.of());
    }

    public boolean isChoice() {
        return kind == StepKind.CHOICE;
    }

    public boolean isMeasure() {
        return kind == StepKind.MEASURE;
    }
}
