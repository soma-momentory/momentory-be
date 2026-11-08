package com.momentory.letter.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.momentory.letter.application.LetterView;
import com.momentory.letter.domain.LetterActionSource;
import com.momentory.letter.domain.LetterStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record LetterResponse(
        @Schema(description = "편지 ID", example = "12") Long id,
        @Schema(description = "편지의 출처 일기 날짜(04:00 경계). 그다음 날부터 열린다", example = "2026-10-26") LocalDate date,
        @Schema(description = "READY: AI 편지 · FALLBACK: 기본 편지 · QUIET: 조용한 편지", example = "READY", type = "string", allowableValues = {"READY", "FALLBACK", "QUIET"}) LetterStatus status,
        @Schema(description = "AI 편지면 어제 사용자가 한 말 그대로(원문 대조를 통과하지 못하면 null), 기본 편지면 일기 첫 문장. 조용한 편지면 null", example = "발표 전에 손이 계속 떨렸어요", nullable = true) String quote,
        @Schema(description = "바바가 알아챈 관점(조용한 편지면 고정 문구)", example = "떨렸던 건 그만큼 잘하고 싶었기 때문이었을지도 몰라요.") String insight,
        @Schema(description = "오늘 밤 첫 질문 예고. 조용한 편지면 null", example = "오늘 밤엔 발표 뒤 마음부터 물어볼게요.", nullable = true) String tonightQuestion,
        @Schema(description = "오늘 해 볼 작은 일(최대 3개). 조용한 편지면 빈 목록") List<Action> actions,
        @Schema(description = "도움받을 곳. 조용한 편지에만 있고 나머지는 빈 목록") List<String> helplines,
        @Schema(description = "받은 날 · 그다음 날이면 true. 홈 아이콘 점은 isNew 이고 openedAt 이 없을 때 단다", example = "true") boolean isNew,
        @Schema(description = "처음 연 시각. 아직 안 열었으면 null", nullable = true) Instant openedAt
) {

    public record Action(
            @Schema(description = "칸 번호 0~2", example = "0") int position,
            @Schema(description = "승인된 행동 제목 그대로", example = "5분 동안 숨을 들이쉬고 길게 내쉬기") String title,
            @Schema(description = "CATALOG: AI 가 연결표에서 고름 · FALLBACK: 연결표 순서대로 채움", example = "CATALOG") LetterActionSource source,
            @Schema(description = "고른 일인가. 한 편지에 하나만 true", example = "false") boolean chosen,
            @Schema(description = "고른 「언제」 칩의 말", example = "점심 뒤", nullable = true) String whenLabel,
            @Schema(description = "일정 칩을 골랐을 때 그 일정 ID", nullable = true) Long scheduleId,
            @Schema(description = "고른 알림 시각. 없으면 알림 없이", nullable = true) Instant remindAt
    ) {
    }

    static LetterResponse from(LetterView view) {
        return new LetterResponse(view.id(), view.date(), view.status(), view.quote(), view.insight(),
                view.tonightQuestion(),
                view.actions().stream().map(a -> new Action(a.position(), a.title(), a.source(), a.chosen(),
                        a.whenLabel(), a.scheduleId(), a.remindAt())).toList(),
                view.helplines(), view.isNew(), view.openedAt());
    }
}
