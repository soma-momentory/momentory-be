package com.momentory.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 하루의 경계 — <b>자정이 아니라 새벽 4시(KST)에 날짜가 넘어간다.</b>
 *
 * <p>새벽 4시 전(00:00~03:59 KST)에 남긴 기록은 아직 「어제」에 속한다. 늦은 밤 회고가
 * 다음 날로 넘어가 버리지 않게 하려는 규칙이다(프론트 {@code src/lib/clock.ts} 의
 * {@code DAY_BOUNDARY_HOUR} 와 같은 값 · 같은 뜻).
 *
 * <p>일기·행동 카드는 별도 날짜 필드 없이 {@code created_at}({@link Instant}) 하나로 어느
 * 날에 속하는지를 정하므로, 「그 순간이 속한 하루」를 구하는 계산을 여기 한곳에 모은다.
 * 순수 KST 계산이라({@link TimeZonePolicy#DEFAULT_ZONE_ID}) 한국은 서머타임이 없어
 * 시각을 빼는 계산이 안전하다.
 */
public final class DayBoundary {

    /** 하루가 넘어가는 시각(KST) — 이 시각 전은 전날이다. */
    public static final int BOUNDARY_HOUR = 4;

    private static final ZoneId ZONE = TimeZonePolicy.DEFAULT_ZONE_ID;

    private DayBoundary() {
    }

    /** 이 순간이 속한 「하루」의 날짜(KST · 04:00 경계). 03:59 는 어제, 04:00 부터 오늘이다. */
    public static LocalDate toLocalDate(Instant at) {
        return at.atZone(ZONE).minusHours(BOUNDARY_HOUR).toLocalDate();
    }

    /** 지금이 속한 「하루」의 날짜(KST · 04:00 경계). */
    public static LocalDate today() {
        return toLocalDate(Instant.now());
    }

    /** 그 「하루」가 시작하는 순간 — 해당 날짜의 04:00 KST. */
    public static Instant startOfDay(LocalDate date) {
        return date.atTime(BOUNDARY_HOUR, 0).atZone(ZONE).toInstant();
    }
}
