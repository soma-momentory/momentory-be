package com.momentory.retrospect.domain.strategy;

/**
 * 이번 턴의 방침 — 유형과 질문 조절값.
 *
 * @param type           방침의 유형
 * @param focus          질문을 누가 붙이는가. NONE 이면 질문 없음
 * @param asChoices      질문을 고를 수 있는 선택지 형태로 가볍게 묻는가(저항이 반복된 사용자)
 * @param reaction       판단 모델이 본 「직전 응답에 대한 반응」 — 문장 생성 모델에 참고로 건넨다. 볼 것이 없으면 null
 * @param askedToBeHeard 사용자가 방금 들어 달라고(묻지 말라고) 했는가 — 이 턴에는 그러겠다고 먼저 답한다
 * @param feelingKnown   지금 이야기에서 그때의 기분을 이미 들었는가(방금 답변 포함) — 문장 생성 모델이 기분을 다시 묻지 않게 한다
 * @param afterOtherMemory 바바가 바로 앞에서 다른 기억을 물었는가 — 사용자가 앞 이야기를 이어 말하면 그 이야기를 따라간다
 * @param askedAfterFeeling 지금 이야기에서 기분을 들은 뒤로 바바가 한 질문 수 — 문장 생성 모델에 이번에 물을 것을 알린다
 * @param hearingOut     사용자가 더 얘기하고 싶다고 한 이야기를 듣고 있는가 — 묻지 않고 공감만 한다
 * @param detailAsked    지금 이야기에서 무슨 일이었는지 구체적으로 묻는 질문을 이미 했는가 — 더 캐묻지 않고 기분으로 넘어간다
 */
public record TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction,
        boolean askedToBeHeard, boolean feelingKnown, boolean afterOtherMemory, int askedAfterFeeling,
        boolean hearingOut, boolean detailAsked) {

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction,
            boolean askedToBeHeard, boolean feelingKnown, boolean afterOtherMemory, int askedAfterFeeling,
            boolean hearingOut) {
        this(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory, askedAfterFeeling,
                hearingOut, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction,
            boolean askedToBeHeard, boolean feelingKnown, boolean afterOtherMemory) {
        this(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory, 0, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction,
            boolean askedToBeHeard, boolean feelingKnown) {
        this(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction,
            boolean askedToBeHeard) {
        this(type, focus, asChoices, reaction, askedToBeHeard, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices, String reaction) {
        this(type, focus, asChoices, reaction, false, false);
    }

    public TurnStrategy(StrategyType type, QuestionFocus focus, boolean asChoices) {
        this(type, focus, asChoices, null, false, false);
    }

    public boolean asks() {
        return focus != QuestionFocus.NONE;
    }

    /** 반응을 뗀다 — 반응으로 본 것이 알고 보니 받아들이지 않은 것이 아니었을 때(새 이야기를 꺼낸 것). */
    public TurnStrategy withoutReaction() {
        return new TurnStrategy(type, focus, asChoices, null, askedToBeHeard, feelingKnown, afterOtherMemory,
                askedAfterFeeling, hearingOut, detailAsked);
    }

    public TurnStrategy withFocus(QuestionFocus newFocus) {
        return new TurnStrategy(type, newFocus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory,
                askedAfterFeeling, hearingOut, detailAsked);
    }

    public TurnStrategy withFeelingKnown(boolean known) {
        return new TurnStrategy(type, focus, asChoices, reaction, askedToBeHeard, known, afterOtherMemory,
                askedAfterFeeling, hearingOut, detailAsked);
    }

    public TurnStrategy withAfterOtherMemory(boolean asked) {
        return new TurnStrategy(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, asked,
                askedAfterFeeling, hearingOut, detailAsked);
    }

    public TurnStrategy withAskedAfterFeeling(int asked) {
        return new TurnStrategy(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory,
                asked, hearingOut, detailAsked);
    }

    public TurnStrategy withHearingOut(boolean hearing) {
        return new TurnStrategy(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory,
                askedAfterFeeling, hearing, detailAsked);
    }

    public TurnStrategy withDetailAsked(boolean asked) {
        return new TurnStrategy(type, focus, asChoices, reaction, askedToBeHeard, feelingKnown, afterOtherMemory,
                askedAfterFeeling, hearingOut, asked);
    }
}
