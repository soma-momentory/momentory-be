package com.momentory.retrospect.application;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.MeasureField;

/**
 * 한 턴의 응답 — 프론트는 이걸 보고 무엇을 그릴지 정한다.
 *
 * <p>{@code options} 가 있으면 선택 버튼, {@code ui="measure"} 면 {@code measures} 의
 * 0~10 슬라이더들, {@code diary} 가 있으면 일기 카드(+ {@code actionCard} 가 있으면 행동 카드).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplyDto(
        String text,
        String phase,
        List<OptionDto> options,
        String ui,
        List<MeasureDto> measures,
        ActionCardDto actionCard,
        DiaryDto diary,
        boolean done,
        String safetyLevel) {

    /** {@code ui} 값 — 프론트가 슬라이더를 띄워야 한다는 신호. */
    public static final String UI_MEASURE = "measure";

    /**
     * 선택지 버튼 하나. {@code id} 는 1-base 번호 문자열 — 턴 요청의 {@code optionId} 로 돌려보낸다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionDto(String id, String label, String description) {
    }

    /** 슬라이더 하나. {@code id} 는 {@link MeasureField} 키 — 턴 요청의 measures 키로 돌려보낸다. */
    public record MeasureDto(String id, String label, int min, int max) {
        public static MeasureDto of(String id, String label) {
            return new MeasureDto(id, label, MeasureField.MIN, MeasureField.MAX);
        }
    }

    /** 행동 카드 (PDF "🪪 행동 카드"). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionCardDto(String situation, String action, String detail,
            String createdDate) {
    }

    /** 일기 — 짧은 기록형은 {@code reframedDiary} 가 없어 프론트가 카드 1장만 그린다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryDto(String diary, String reframedDiary) {
    }

    static ReplyDto question(String text, Phase phase, SafetyLevel safety) {
        return new ReplyDto(text, phase.key(), null, null, null, null, null, false, safety.key());
    }

    static ReplyDto choices(String text, Phase phase, List<OptionDto> options,
            SafetyLevel safety) {
        return new ReplyDto(text, phase.key(), options, null, null, null, null, false,
                safety.key());
    }

    static ReplyDto measure(String text, Phase phase, List<MeasureDto> measures,
            SafetyLevel safety) {
        return new ReplyDto(text, phase.key(), null, UI_MEASURE, measures, null, null, false,
                safety.key());
    }

    static ReplyDto completed(String text, DiaryDto diary, ActionCardDto actionCard,
            SafetyLevel safety) {
        return new ReplyDto(text, Phase.COMPLETE.key(), null, null, null, actionCard, diary, true,
                safety.key());
    }

    static ReplyDto ended(String text, SafetyLevel safety) {
        return new ReplyDto(text, Phase.ENDED.key(), null, null, null, null, null, true,
                safety.key());
    }

    static ReplyDto alreadyFinished(Phase phase) {
        return new ReplyDto("이번 회고는 이미 마무리됐어요.", phase.key(), null, null, null, null,
                null, true, SafetyLevel.NONE.key());
    }
}
