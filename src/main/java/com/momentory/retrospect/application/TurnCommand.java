package com.momentory.retrospect.application;

import java.util.Map;

/**
 * 사용자가 한 턴에 보내는 것 — 셋 중 그 턴에 맞는 하나만 채워진다.
 *
 * @param content  자유 텍스트 답변
 * @param optionId 선택지 턴에서 고른 버튼 id (1-base 번호 문자열)
 * @param measures 측정 턴에서 슬라이더 값들. measureField 키 → 0~10 값.
 */
public record TurnCommand(String content, String optionId, Map<String, Integer> measures) {

    /**
     * 자유 텍스트 방어 상한(글자). 프론트는 이보다 낮게(500) 막지만, 직접 API 호출로 우회하는
     * 경우 대비 서버도 아주 후한 캡에서 절단한다 — 프롬프트 토큰 폭주를 막는 안전장치.
     * 정상 사용(프론트 경유)에서는 절대 걸리지 않는다.
     */
    public static final int MAX_CONTENT_LENGTH = 1000;

    public TurnCommand {
        measures = measures == null ? Map.of() : Map.copyOf(measures);
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
    }

    public static TurnCommand text(String content) {
        return new TurnCommand(content, null, Map.of());
    }

    public static TurnCommand option(String optionId) {
        return new TurnCommand(null, optionId, Map.of());
    }

    public static TurnCommand measures(Map<String, Integer> measures) {
        return new TurnCommand(null, null, measures);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean hasOption() {
        return optionId != null && !optionId.isBlank();
    }

    public boolean hasMeasures() {
        return !measures.isEmpty();
    }
}
