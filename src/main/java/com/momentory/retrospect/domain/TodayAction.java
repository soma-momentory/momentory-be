package com.momentory.retrospect.domain;

/**
 * 오늘 해 보기로 한 작은 일 — 아침 편지에서 고른 일이다. 회고를 시작할 때 편지 쪽이 찾아 넣어 주고,
 * 회고는 첫 질문에서 해 봤는지 묻는다(바바의 편지 PRD 4-5 · 14-4).
 *
 * @param letterActionId 편지의 작은 일 칸 id — 답을 기록할 곳
 * @param title          작은 일 제목(승인된 문구 그대로)
 * @param alreadyDone    쉼터 바람카드에서 이미 「했어요」를 눌렀는가
 * @param followUp       답을 받은 뒤 이어서 물을 원래 첫 질문(일정 · 「가장 기억에 남는 일」)
 * @param awaiting       첫 질문의 답을 아직 기다리는가
 */
public record TodayAction(Long letterActionId, String title, boolean alreadyDone, String followUp,
        boolean awaiting) {

    /** 회고 시작 전 — 편지 쪽이 찾은 값. 이어서 물을 질문은 엔진이 채운다. */
    public static TodayAction of(Long letterActionId, String title, boolean alreadyDone) {
        return new TodayAction(letterActionId, title, alreadyDone, null, false);
    }

    TodayAction asked(String followUp) {
        return new TodayAction(letterActionId, title, alreadyDone, followUp, true);
    }

    TodayAction answered() {
        return new TodayAction(letterActionId, title, alreadyDone, followUp, false);
    }
}
