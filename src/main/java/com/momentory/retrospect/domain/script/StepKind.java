package com.momentory.retrospect.domain.script;

/** 스크립트 한 턴의 입력 형태 — 프론트가 무엇을 그릴지 결정한다. */
public enum StepKind {

    /** 자유 텍스트 답변. */
    TEXT,
    /** 보기 중 하나 선택(버튼). 보기 문구는 AI가 대화 맥락에 맞게 생성한다. */
    CHOICE,
    /** 0~10 슬라이더 측정. 문구는 고정 템플릿 — AI 호출 0회. */
    MEASURE
}
