package com.momentory.retrospect.domain.script;

/**
 * 선택지 버튼 하나 — 값 객체.
 *
 * @param label       버튼에 보이는 문구 (예: "답변 핵심 세 줄 만들기")
 * @param description 행동 선택지의 한 줄 설명 (예: "질문 하나를 결론·이유·경험으로 정리하기"). 없으면 null.
 */
public record OptionItem(String label, String description) {

    public static OptionItem of(String label) {
        return new OptionItem(label, null);
    }

    public boolean hasDescription() {
        return description != null && !description.isBlank();
    }

    /** 대화 기록·일기 프롬프트에 남길 한 줄. */
    public String toLine() {
        return hasDescription() ? label + " — " + description : label;
    }
}
