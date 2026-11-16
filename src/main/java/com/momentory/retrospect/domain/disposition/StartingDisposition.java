package com.momentory.retrospect.domain.disposition;

import java.util.List;

/**
 * 지난 대화들에서 도출한, 다음 대화를 어떻게 시작할지 — 성격 라벨이 아니라 행동 선호다.
 *
 * <p>같은 신호가 여러 대화에서 되풀이될 때만 도출한다. 하루 컨디션을 성향으로 굳히지 않기 위해서다. 감정 강도는 재기만 하고
 * 여기에 쓰지 않는다(근거가 약하고, 모두에게 기분부터 묻는 지금의 대화와 부딪힌다). 「들어 달라」는 요청도 쓰지 않는다 —
 * 질문 없는 받아주기는 그 대화에서 명시적으로 요청했을 때만 한다.
 *
 * @param selfLed        스스로 이끌고 싶어 한다 — 질문을 드물게, 고를 수 있는 형태로 묻는다
 * @param wantsOtherView 다른 관점을 원한다 — 재해석 방침으로 시작한다
 */
public record StartingDisposition(boolean selfLed, boolean wantsOtherView) {

    public static final StartingDisposition NONE = new StartingDisposition(false, false);

    /**
     * @param recent              최근 대화들의 신호(반영할 범위만큼만 넘긴다)
     * @param repeatThreshold     같은 신호가 이만큼의 대화에서 나와야 도출한다
     * @param resistanceThreshold 한 대화 안에서 저항이 이 횟수 이상이면 그 대화를 「스스로 이끌고 싶어 한 대화」로 센다
     *                            (대화 안에서 질문을 줄이기 시작하는 기준과 같다)
     */
    public static StartingDisposition of(List<ConversationSignals> recent, int repeatThreshold,
            int resistanceThreshold) {
        long selfLed = recent.stream().filter(c -> c.resistanceCount() >= resistanceThreshold).count();
        long otherView = recent.stream().filter(c -> c.otherViewRequestCount() > 0).count();
        return new StartingDisposition(selfLed >= repeatThreshold, otherView >= repeatThreshold);
    }

    public boolean isNone() {
        return !selfLed && !wantsOtherView;
    }
}
