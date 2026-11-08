package com.momentory.weeklyhappiness.application;

public class WeeklyHappinessException extends RuntimeException {

    private final String code;

    private WeeklyHappinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static WeeklyHappinessException invalidWeekStart() {
        return new WeeklyHappinessException("WEEKLY_HAPPINESS_INVALID_WEEK_START", "주의 첫날(일요일) 날짜를 보내주세요.");
    }

    public static WeeklyHappinessException futureWeek() {
        return new WeeklyHappinessException("WEEKLY_HAPPINESS_FUTURE_WEEK", "아직 오지 않은 주입니다.");
    }

    public static WeeklyHappinessException pastWeek() {
        return new WeeklyHappinessException("WEEKLY_HAPPINESS_PAST_WEEK", "지난 주는 고칠 수 없습니다.");
    }

    public String code() {
        return code;
    }
}
