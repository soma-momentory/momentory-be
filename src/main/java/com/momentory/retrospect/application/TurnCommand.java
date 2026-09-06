package com.momentory.retrospect.application;

import java.util.List;

/**
 * 사용자가 한 턴에 보내는 것 (채팅흐름_v2) — 자유 텍스트이거나 선택지들이다.
 *
 * @param content    자유 텍스트 답변("직접 적기" 포함). 일기 작성 턴은 이걸로만 온다.
 * @param optionIds  고른 선택지 번호(1-base 문자열)들 — 분기점은 1개, 감정·바람은 최대 2개.
 */
public record TurnCommand(String content, List<String> optionIds) {

    /**
     * 자유 텍스트 방어 상한(글자). 프론트는 낮게(500) 막지만, 직접 API 호출 우회 대비 서버도 후한
     * 캡에서 절단한다 — 프롬프트 토큰 폭주 방지. 정상 사용에서는 걸리지 않는다.
     */
    public static final int MAX_CONTENT_LENGTH = 1000;

    public TurnCommand {
        optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
    }

    public static TurnCommand text(String content) {
        return new TurnCommand(content, List.of());
    }

    public static TurnCommand option(String optionId) {
        return new TurnCommand(null, optionId == null ? List.of() : List.of(optionId));
    }

    public static TurnCommand options(List<String> optionIds) {
        return new TurnCommand(null, optionIds);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean hasOptions() {
        return !optionIds.isEmpty();
    }

    /** 단일 선택 턴(분기점)용 — 첫 선택지 번호. 없으면 null. */
    public String firstOption() {
        return optionIds.isEmpty() ? null : optionIds.get(0);
    }
}
