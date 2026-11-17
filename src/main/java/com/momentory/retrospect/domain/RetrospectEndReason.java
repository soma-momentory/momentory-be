package com.momentory.retrospect.domain;

/**
 * 회고가 어떻게 끝났는가 — 연구 지표(미완료 이탈률)에서 정상 정리 · 사용자 요청 · 상한 정리를 가르는 데 쓴다.
 * 안전 대응으로 멈춘 것은 끝난 것이 아니어서 여기에 없다(사용자가 이어서 할 수 있다).
 */
public enum RetrospectEndReason {

    /** 마무리 제안이나 단답 체크인에서 「이제 정리할래요」를 골랐다. */
    WRAP_UP_ACCEPTED,
    /** 다른 기억을 묻는 질문에 없다고 답했다. */
    NO_OTHER_MEMORY,
    /** 사용자가 끝내려 했다 — 판단 모델의 종료 판단이나 분명한 종료 문구. */
    USER_REQUEST,
    /** 일기 작성 채팅이 턴 상한에 닿아 정리했다. */
    TURN_CAP,
    /** 악용 시도가 이어져 대화를 멈췄다. */
    ABUSE_STOPPED,
    /** 배포 전 감정 탐색 단계에 있던 세션을 일기로 정리했다. */
    LEGACY_EXPLORATION
}
