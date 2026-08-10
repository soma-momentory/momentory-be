package com.momentory.user.domain;

public enum RestMethod {
    SLEEP("잠자기"),
    MEDITATION("명상하기"),
    ENJOYING_FOOD("맛있는 음식 먹기"),
    LISTENING_TO_MUSIC("음악 듣기"),
    WATCHING_MOVIES_OR_DRAMAS("영화, 드라마 보기"),
    READING("독서하기"),
    WRITING("글쓰기"),
    VISITING_A_CAFE("카페가기"),
    WALKING("산책하기"),
    TALKING_WITH_CLOSE_PEOPLE("가까운 사람과 대화하기"),
    EXERCISE("운동하기"),
    GAMING("게임하기"),
    VARIES_BY_DAY("그때마다 달라요"),
    IDLE_REST("멍하니 쉬기"),
    OTHER("기타");

    private final String label;

    RestMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
