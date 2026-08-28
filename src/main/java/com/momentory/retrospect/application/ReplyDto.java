package com.momentory.retrospect.application;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 한 턴의 응답 (채팅흐름_v2) — 프론트는 이걸 보고 무엇을 그릴지 정한다.
 *
 * <p>{@code options} 가 있으면 선택 버튼, {@code diary} 가 있으면 일기 카드, {@code wishCard} 가
 * 있으면(감정 탐색을 거친 경우) 바람 카드. 최종 종료면 {@code done=true}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplyDto(
        String text,
        String phase,
        List<OptionDto> options,
        DiaryDto diary,
        WishCardDto wishCard,
        boolean done,
        String safetyLevel) {

    /**
     * 선택지 버튼 하나. {@code id} 는 1-base 번호 문자열 — 턴 요청의 {@code optionIds} 로 돌려보낸다.
     * {@code input} 이 true 면 "직접 적기" 선지라 프론트가 텍스트 필드를 연다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionDto(String id, String label, String description, String hint,
            Boolean input) {

        public OptionDto(String id, String label, String description) {
            this(id, label, description, null, null);
        }
    }

    /**
     * 일기. {@code diaryId} 는 완료 턴이 방금 저장한 일기의 id — 엔진은 텍스트만 만들고 저장은 이벤트가
     * 맡으므로 null 로 두고, 저장 뒤 {@link #withDiaryId} 가 채운다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiaryDto(Long diaryId, String diary, String reframedDiary) {
    }

    /**
     * 바람 카드 — 감정 탐색을 거친 경우에만 실린다. {@code wishCardId} 는 완료 턴이 방금 저장한 카드의
     * id(저장 뒤 {@link #withWishCardId} 가 채운다). 비운 항목은 생략(NON_NULL)된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WishCardDto(Long wishCardId, String situation, List<String> emotions,
            List<NeedDto> needs, String desiredState, String smallAction, String sentiment) {
    }

    /** 바람(욕구) 하나 — 단어 + 뜻. */
    public record NeedDto(String word, String meaning) {
    }

    ReplyDto withDiaryId(Long diaryId) {
        if (diary == null) {
            return this;
        }
        DiaryDto withId = new DiaryDto(diaryId, diary.diary(), diary.reframedDiary());
        return new ReplyDto(text, phase, options, withId, wishCard, done, safetyLevel);
    }

    ReplyDto withWishCardId(Long wishCardId) {
        if (wishCard == null) {
            return this;
        }
        WishCardDto withId = new WishCardDto(wishCardId, wishCard.situation(), wishCard.emotions(),
                wishCard.needs(), wishCard.desiredState(), wishCard.smallAction(),
                wishCard.sentiment());
        return new ReplyDto(text, phase, options, diary, withId, done, safetyLevel);
    }

    static ReplyDto question(String text, Phase phase, SafetyLevel safety) {
        return new ReplyDto(text, phase.key(), null, null, null, false, safety.key());
    }

    static ReplyDto choices(String text, Phase phase, List<OptionDto> options, SafetyLevel safety) {
        return new ReplyDto(text, phase.key(), options, null, null, false, safety.key());
    }

    static ReplyDto completed(String text, DiaryDto diary, WishCardDto wishCard,
            SafetyLevel safety) {
        return new ReplyDto(text, Phase.COMPLETE.key(), null, diary, wishCard, true, safety.key());
    }

    static ReplyDto ended(String text, SafetyLevel safety) {
        return new ReplyDto(text, Phase.ENDED.key(), null, null, null, true, safety.key());
    }

    static ReplyDto safetyHold(String text, SafetyLevel safety) {
        return new ReplyDto(text, Phase.SAFETY_HOLD.key(), null, null, null, false, safety.key());
    }

    static ReplyDto alreadyFinished(Phase phase) {
        return new ReplyDto("이번 회고는 이미 마무리됐어요.", phase.key(), null, null, null, true,
                SafetyLevel.NONE.key());
    }
}
