package com.momentory.wardrobe.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 출석 이레 — 날마다 이어 오면 칸이 하나씩 넘어가고, 이레째 다음이나 하루를 거르면 첫 칸으로 돌아간다.
 * 거른 것을 따로 알리지 않는다(앱은 첫 칸을 다시 보일 뿐이다).
 */
public final class Attendance {

    /** 칸마다 받는 귤 — 이어 올수록 조금씩 늘어난다 */
    public static final List<Integer> REWARDS = List.of(10, 10, 20, 20, 30, 40, 50);

    private Attendance() {
    }

    /** 오늘이 몇째 칸인가(1~7). {@code checkInDays} 는 출석 보상을 받은 날들이다(오늘이 있어도 없어도 된다). */
    public static int dayOf(Set<LocalDate> checkInDays, LocalDate today) {
        int before = 0;
        while (checkInDays.contains(today.minusDays(before + 1L))) {
            before++;
        }
        return before % REWARDS.size() + 1;
    }

    public static int reward(int day) {
        return REWARDS.get(day - 1);
    }
}
