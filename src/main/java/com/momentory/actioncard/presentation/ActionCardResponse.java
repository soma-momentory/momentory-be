package com.momentory.actioncard.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.actioncard.application.ActionCardView;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.Needs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 바람 카드(v2) 한 장 조회 응답 — 보관함 목록의 항목이자 단건 조회의 본체.
 *
 * <p>{@code emotions} 는 감정 키 목록(프론트가 이름으로 매핑), {@code needs} 는 바람 단어+뜻,
 * {@code smallAction}(= 목표 행동)·{@code desiredState}·{@code sentiment} 는 감정 탐색을 거친
 * 카드에만 채워진다. {@code doneAt}/{@code reflection} 은 "해봤어요" 이후에만.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionCardResponse(
        @Schema(description = "바람 카드 id", example = "1") Long id,
        @Schema(description = "그날의 회고 id — 일기 조회에 쓴다", example = "10") Long retrospectId,
        @Schema(description = "상황", example = "팀플 회의에서 의견을 말하던 중 팀원이 말을 끊었다") String situation,
        @Schema(description = "확인된 감정 키 목록", example = "[\"angry\", \"frustrated\"]") List<String> emotions,
        @Schema(description = "내 마음이 바랐던 것(단어+뜻)") List<NeedDto> needs,
        @Schema(description = "바랐던 모습", example = "내 말을 끝까지 듣고 나서 의견을 말해주는 것") String desiredState,
        @Schema(description = "작은 행동(없을 수 있음)", example = "회의가 끝난 뒤 느낀 점을 한 문장으로 전해보기") String targetAction,
        @Schema(description = "감정 성격", example = "uncomfortable") String sentiment,
        @Schema(description = "만든 날짜", example = "2026-08-10") LocalDate createdDate,
        @Schema(description = "해봤는지 여부", example = "false") boolean done,
        @Schema(description = "해본 시각 — 해본 뒤에만 채워짐", example = "2026-08-12T09:20:11Z") Instant doneAt,
        @Schema(description = "느낀 점 — 해본 뒤에만 채워짐") String reflection,
        @Schema(description = "생성 시각", example = "2026-08-10T02:23:47.850Z") Instant createdAt) {

    /** 바람(욕구) 하나 — 단어 + 뜻(고정 목록에서 역참조, 직접 적은 단어면 뜻 없음). */
    public record NeedDto(String word, String meaning) {
    }

    static ActionCardResponse from(ActionCardView view) {
        List<NeedDto> needs = view.needWords().stream()
                .map(w -> new NeedDto(w, Needs.byWord(w).map(Need::meaning).orElse(null)))
                .toList();
        return new ActionCardResponse(view.id(), view.retrospectId(), view.situation(),
                view.emotions(), needs, view.desiredState(), view.targetAction(), view.sentiment(),
                view.createdDate(), view.done(), view.doneAt(), view.reflection(), view.createdAt());
    }
}
