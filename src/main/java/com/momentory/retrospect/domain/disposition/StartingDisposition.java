package com.momentory.retrospect.domain.disposition;

import java.util.List;

/**
 * 지난 대화들에서 도출한, 다음 대화를 어떻게 시작할지 — 성격 라벨이 아니라 행동 선호다.
 *
 * <p>초반에는 한 번의 신호로도 반영하고, 그 뒤로는 성향별 반복 기준을 따른다({@code DispositionStart} 가 기준을 고른다).
 * 주도성은 저항이 되풀이된 대화만 세므로 한 대화로도 반복을 본 것이고, 다른 관점은 요청이 여러 대화에서 나와야 한다.
 * 두 성향이 함께 나오면 더 많이 나온 하나만 반영한다. 감정 강도는 재기만 하고
 * 여기에 쓰지 않는다(근거가 약하고, 모두에게 기분부터 묻는 지금의 대화와 부딪힌다). 「들어 달라」는 요청도 쓰지 않는다 —
 * 질문 없는 받아주기는 그 대화에서 명시적으로 요청했을 때만 한다.
 *
 * @param selfLed        스스로 이끌고 싶어 한다 — 질문을 드물게, 고를 수 있는 형태로 묻는다
 * @param wantsOtherView 다른 관점을 원한다 — 재해석 방침으로 시작한다
 */
public record StartingDisposition(boolean selfLed, boolean wantsOtherView) {

    public static final StartingDisposition NONE = new StartingDisposition(false, false);

    /**
     * @param recent              최근 대화들의 신호 — 최근 것부터, 반영할 범위만큼만 넘긴다
     * @param selfLedRepeat       「스스로 이끌고 싶어 한 대화」가 이만큼 있어야 도출한다
     * @param otherViewRepeat     다른 관점을 요청한 대화가 이만큼 있어야 도출한다
     * @param resistanceThreshold 한 대화 안에서 저항이 이 횟수 이상이면 그 대화를 「스스로 이끌고 싶어 한 대화」로 센다
     *                            (대화 안에서 질문을 줄이기 시작하는 기준과 같다)
     */
    public static StartingDisposition of(List<ConversationSignals> recent, int selfLedRepeat, int otherViewRepeat,
            int resistanceThreshold) {
        long selfLed = recent.stream().filter(c -> c.resistanceCount() >= resistanceThreshold).count();
        long otherView = recent.stream().filter(c -> c.otherViewRequestCount() > 0).count();
        boolean selfLedMet = selfLed >= selfLedRepeat;
        boolean otherViewMet = otherView >= otherViewRepeat;
        if (selfLedMet && otherViewMet) {
            return dominant(recent, selfLed, otherView, resistanceThreshold);
        }
        return new StartingDisposition(selfLedMet, otherViewMet);
    }

    /** 더 많이 나온 성향 하나. 같으면 더 최근에 나온 쪽이고, 한 대화에서 함께 나왔으면 직접 요청한 다른 관점이다. */
    private static StartingDisposition dominant(List<ConversationSignals> recent, long selfLed, long otherView,
            int resistanceThreshold) {
        if (selfLed != otherView) {
            return new StartingDisposition(selfLed > otherView, otherView > selfLed);
        }
        for (ConversationSignals conversation : recent) {
            if (conversation.otherViewRequestCount() > 0) {
                return new StartingDisposition(false, true);
            }
            if (conversation.resistanceCount() >= resistanceThreshold) {
                return new StartingDisposition(true, false);
            }
        }
        return NONE;
    }

    public boolean isNone() {
        return !selfLed && !wantsOtherView;
    }
}
