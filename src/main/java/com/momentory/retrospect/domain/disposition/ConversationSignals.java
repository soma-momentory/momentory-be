package com.momentory.retrospect.domain.disposition;

/** 완료된 회고 한 건에서 저장한 세 차원의 관찰 신호. */
public record ConversationSignals(int resistanceCount, int listenRequestCount, int otherViewRequestCount,
        int feelingExpressedCount, int feelingAvoidedCount) {
}
