package com.momentory.retrospect.domain.disposition;

/**
 * 저장한 성향을 대화 시작에 반영하는 실험의 집단. 대화마다 기록해 반영한 대화와 하지 않은 대화를 견줄 수 있게 한다.
 */
public enum DispositionArm {
    /** 실험에 들지 않았다 — 반영 기능이 꺼져 있거나 대상 계정이 아니다. 기본값에서 시작한다. */
    NONE,
    /** 반영 켬 — 지난 대화에서 도출된 성향이 있으면 그 성향으로 시작한다. */
    TREATMENT,
    /** 반영 끔 — 성향은 똑같이 저장하고 도출하지만 기본값에서 시작한다. */
    CONTROL
}
