package com.momentory.retrospect.domain.script;

/**
 * 회고 유형 5종 (채팅 최적 시나리오 PDF).
 *
 * <p>유형마다 고정된 턴 스크립트({@link Scripts})가 있고, 산출물 구성이 다르다 —
 * 행동 카드는 인지 재구성·강점·문제 해결형만, 리프레이밍 일기는 짧은 기록형만 없다.
 */
public enum RetroMode {

    EMOTION_SORTING("emotion_sorting", "감정 정리형", false, true,
            "감정을 충분히 알아주는 회고였다. 리프레이밍 일기는 '오늘 힘들었던 마음을 먼저 알아주자'는 "
                    + "자기 위로와 사용자가 자신에게 남긴 말을 중심으로 쓴다."),
    REFRAME("reframe", "인지 재구성형", true, true,
            "자동적 사고를 검증한 회고였다. 리프레이밍 일기는 사용자가 정리한 균형 잡힌 생각과 "
                    + "반대 증거를 중심으로, 관점이 조금 달라진 느낌이 나게 쓴다."),
    STRENGTH("strength", "강점 기반형", true, true,
            "이전과 다르게 해낸 점을 찾은 회고였다. 리프레이밍 일기는 발견한 강점과 달라진 행동을 "
                    + "중심으로 쓴다. '잘한 것은 완벽함이 아니라 다시 이어간 것'이라는 결."),
    PROBLEM_SOLVING("problem_solving", "문제 해결형", true, true,
            "통제 가능한 부분과 첫 행동을 정한 회고였다. 리프레이밍 일기는 '바꿀 수 없는 것과 "
                    + "할 수 있는 것'을 구분한 관점과 정한 행동을 중심으로 쓴다."),
    SHORT_RECORD("short_record", "짧은 기록형", false, false,
            "짧게 기록만 남기는 회고였다. 일기는 사용자가 남긴 한 문장과 감정 강도를 살려 담백하게 쓴다.");

    private final String key;
    private final String label;
    private final boolean actionCard;
    private final boolean reframedDiary;
    private final String diaryHint;

    RetroMode(String key, String label, boolean actionCard, boolean reframedDiary,
            String diaryHint) {
        this.key = key;
        this.label = label;
        this.actionCard = actionCard;
        this.reframedDiary = reframedDiary;
        this.diaryHint = diaryHint;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 회고 끝에 행동 카드를 만드는 유형인가. */
    public boolean hasActionCard() {
        return actionCard;
    }

    /** 일기 2종(그냥+리프레이밍)을 만드는가. false면 그냥 일기 1종만. */
    public boolean hasReframedDiary() {
        return reframedDiary;
    }

    /** 일기 생성 프롬프트에 넣는 유형별 관점 힌트. */
    public String diaryHint() {
        return diaryHint;
    }
}
