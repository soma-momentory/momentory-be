package com.momentory.retrospect.domain.disposition;

import java.util.List;

/**
 * 최근 완료된 회고에서 읽은 세 차원의 시작값. 반복 횟수로 적용을 미루지 않는다.
 * 주도성은 저항 신호 하나부터 질문을 줄인다. 지지 대 도전은 경청/다른 관점 요청의 다수 쪽이고,
 * 동수면 가장 최근 회고의 요청을 따른다. 감정 표현보다 회피가 많으면 기분을 직접 묻는 빈도를 줄인다.
 * 신호가 없는 차원은 기본값이다. 현재 대화의 요청과 반응이 이 시작값보다 우선한다.
 */
public record StartingDisposition(boolean selfLed, boolean wantsOtherView, boolean wantsListening,
        boolean gentleEmotion, int historySessions) {

    public static final String VERSION = "always-v2";
    public static final StartingDisposition NONE = new StartingDisposition(false, false, false, false, 0);

    public StartingDisposition(boolean selfLed, boolean wantsOtherView) {
        this(selfLed, wantsOtherView, false, false, 0);
    }

    public static StartingDisposition of(List<ConversationSignals> recent) {
        int resistance = recent.stream().mapToInt(ConversationSignals::resistanceCount).sum();
        int listening = recent.stream().mapToInt(ConversationSignals::listenRequestCount).sum();
        int otherView = recent.stream().mapToInt(ConversationSignals::otherViewRequestCount).sum();
        int expressed = recent.stream().mapToInt(ConversationSignals::feelingExpressedCount).sum();
        int avoided = recent.stream().mapToInt(ConversationSignals::feelingAvoidedCount).sum();
        if (listening == otherView && listening > 0) {
            for (ConversationSignals conversation : recent) {
                if (conversation.listenRequestCount() != conversation.otherViewRequestCount()) {
                    listening = conversation.listenRequestCount();
                    otherView = conversation.otherViewRequestCount();
                    break;
                }
            }
        }
        return new StartingDisposition(resistance > 0, otherView > listening, listening > otherView,
                avoided > expressed, recent.size());
    }
}
