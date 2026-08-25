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
     *
     * <p>{@code hint} 는 화면 배지용 맥락 한 줄(예: "지난 비슷한 상황에서 정한 행동"), {@code input}
     * 이 true 면 "직접 입력" 옵션이라 프론트가 텍스트 필드를 연다. 둘 다 없으면(NON_NULL) 생략된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionDto(String id, String label, String description, String hint,
            Boolean input) {

        /** 배지·직접입력이 없는 보통 옵션용 — 방향·일정·감정 선택지 등. */
        public OptionDto(String id, String label, String description) {
            this(id, label, description, null, null);
        }
    }

    /** 슬라이더 하나. {@code id} 는 {@link MeasureField} 키 — 턴 요청의 measures 키로 돌려보낸다. */
    public record MeasureDto(String id, String label, int min, int max) {
        public static MeasureDto of(String id, String label) {
            return new MeasureDto(id, label, MeasureField.MIN, MeasureField.MAX);
        }
    }

    /**
     * 행동 카드 (PDF "🪪 행동 카드"). {@code action} 하나에 실행할 행동을 상세히 담는다.
     *
     * <p>{@code actionCardId} 는 완료 턴이 서버에 <b>방금 저장한</b> 행동 카드의 id 다(일기의
     * {@code diaryId} 와 같은 결). 엔진은 텍스트만 만들고 id 를 모르므로 {@code null} 로 두고,
     * 저장이 끝난 뒤 {@link #withActionCardId} 가 채운다. 클라이언트는 이 id 로 다음 조회를
     * 기다리지 않고 「해봤어요」·느낀 점({@code PUT /action-cards/{id}/completion})을 곧바로 보낸다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionCardDto(Long actionCardId, String situation, String action,
            String createdDate) {
    }

    /**
     * 일기 — 짧은 기록형은 {@code reframedDiary} 가 없어 프론트가 카드 1장만 그린다.
     *
     * <p>{@code diaryId} 는 완료 턴이 서버에 <b>방금 저장한</b> 일기의 id 다. 엔진은 텍스트만 만들고
     * (저장은 이벤트가 맡는다) id 를 모르므로 {@code null} 로 두고, 저장이 끝난 뒤 {@link #withDiaryId}
     * 가 채운다. 클라이언트는 이 id 를 그대로 들고 있다가 삭제({@code DELETE /diaries/{id}})에 쓴다 —
     * 다음 조회를 기다릴 필요가 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryDto(Long diaryId, String diary, String reframedDiary) {
    }

    /**
     * 저장된 일기 id 를 실은 사본을 돌려준다 — 완료 턴에서만 부른다({@code diary} 가 있을 때).
     * 엔진이 만든 응답에는 id 가 없어, 저장을 맡는 이벤트가 끝난 뒤 서비스가 채워 넣는다.
     */
    ReplyDto withDiaryId(Long diaryId) {
        if (diary == null) {
            return this;
        }
        DiaryDto withId = new DiaryDto(diaryId, diary.diary(), diary.reframedDiary());
        return new ReplyDto(text, phase, options, ui, measures, actionCard, withId, done,
                safetyLevel);
    }

    /**
     * 저장된 행동 카드 id 를 실은 사본을 돌려준다 — 완료 턴에서만 부른다({@code actionCard} 가
     * 있을 때). 일기의 {@link #withDiaryId} 와 같은 결이다.
     */
    ReplyDto withActionCardId(Long actionCardId) {
        if (actionCard == null) {
            return this;
        }
        ActionCardDto withId = new ActionCardDto(actionCardId, actionCard.situation(),
                actionCard.action(), actionCard.createdDate());
        return new ReplyDto(text, phase, options, ui, measures, withId, diary, done, safetyLevel);
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

    /**
     * 위기 안내 후 멈춤 — 안내 문안을 담되 {@code done=false} 다(종결 아님). 프론트는 이 phase 를 보고
     * 「홈으로 돌아가기」와 「이어서 얘기하기」를 함께 띄운다. 이어가기를 고르면 다음 발화가 그대로
     * 다음 턴으로 가서 멈추기 전 phase 에서 이어진다.
     */
    static ReplyDto safetyHold(String text, SafetyLevel safety) {
        return new ReplyDto(text, Phase.SAFETY_HOLD.key(), null, null, null, null, null, false,
                safety.key());
    }

    static ReplyDto alreadyFinished(Phase phase) {
        return new ReplyDto("이번 회고는 이미 마무리됐어요.", phase.key(), null, null, null, null,
                null, true, SafetyLevel.NONE.key());
    }
}
