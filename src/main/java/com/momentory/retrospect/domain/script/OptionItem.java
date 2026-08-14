package com.momentory.retrospect.domain.script;

/**
 * 선택지 버튼 하나 — 값 객체.
 *
 * @param label       버튼에 보이는 문구 (예: "답변 핵심 세 줄 만들기")
 * @param description 행동 선택지의 한 줄 설명 (예: "질문 하나를 결론·이유·경험으로 정리하기"). 없으면 null.
 * @param hint        화면 배지용 한 줄 — 카드 내용이 아니라 맥락 표시다(예: "지난 비슷한 상황에서 정한 행동").
 *                    행동 카드로 굳지 않는다(카드는 {@code label}·{@code description} 만 쓴다). 없으면 null.
 * @param input       true 면 "직접 입력" 옵션 — 고르면 프론트가 텍스트 필드를 열고, 서버는 그 자유
 *                    텍스트를 행동으로 받는다(optionId 로 확정하지 않는다).
 * @param restPreference true 면 온보딩 '쉬는 방법' 선호를 반영해 만든 보기 — <b>분석용 내부 표식</b>이다.
 *                    사용자에게 노출하지 않고({@code OptionDto} 로 나가지 않는다) 행동 카드 저장 때만 쓴다.
 *                    쉬는 행동 카드(care_action) 에서만 true 가 된다.
 */
public record OptionItem(String label, String description, String hint, boolean input,
        boolean restPreference) {

    /** 기존 4-인자 형태 — 선호 표식 없음(false). */
    public OptionItem(String label, String description, String hint, boolean input) {
        this(label, description, hint, input, false);
    }

    /** AI·폴백 옵션용 — 힌트 없음, 직접 입력 아님. */
    public OptionItem(String label, String description) {
        this(label, description, null, false, false);
    }

    public static OptionItem of(String label) {
        return new OptionItem(label, null);
    }

    /** "직접 입력" 옵션 — 고르면 텍스트 필드가 열린다. */
    public static OptionItem input(String label, String hint) {
        return new OptionItem(label, null, hint, true);
    }

    /** 쉬는 방법 선호를 반영해 만든 옵션 — 분석용 내부 표식이 붙는다(사용자 비노출). */
    public static OptionItem restPreference(String label, String description) {
        return new OptionItem(label, description, null, false, true);
    }

    public boolean hasDescription() {
        return description != null && !description.isBlank();
    }

    public boolean hasHint() {
        return hint != null && !hint.isBlank();
    }

    public boolean isInput() {
        return input;
    }

    /** 대화 기록·일기 프롬프트에 남길 한 줄. */
    public String toLine() {
        return hasDescription() ? label + " — " + description : label;
    }
}
