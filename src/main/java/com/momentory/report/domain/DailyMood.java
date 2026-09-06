package com.momentory.report.domain;

import java.time.LocalDate;
import java.util.List;

import com.momentory.retrospect.domain.Emotion;

/**
 * 한 주의 마음 일곱 칸 중 하루 — 그날(KST) 일기에 남은 감정이다.
 *
 * <p>{@code emotion} 은 <b>대표 감정</b>이다(점 하나가 그 색을 맡고, 주간 최다 감정도 이걸 센다).
 * {@code emotions} 는 그날 감정 <b>전부</b>로, 대표 감정이 맨 앞이고 나머지 태그가 잇는다 —
 * "늦게 일어나서 기분 안좋았는데 라면 먹고 기분 좋아졌어" 같은 하루는 둘이 남는데, 점이 하나라
 * 화면은 눌러야 나머지를 볼 수 있다.
 *
 * <p>일기를 남기지 않은 날은 {@code emotion} 이 null 이고 {@code emotions} 가 빈 목록이다
 * (리포트는 그 날도 빈 칸으로 보여준다).
 */
public record DailyMood(LocalDate date, Emotion emotion, List<Emotion> emotions) {

    public DailyMood {
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
    }

    /** 그날 감정 목록에서 만든다 — 대표 감정은 맨 앞이다(비었으면 기록 없는 날). */
    public static DailyMood of(LocalDate date, List<Emotion> emotions) {
        List<Emotion> all = emotions == null ? List.of() : List.copyOf(emotions);
        return new DailyMood(date, all.isEmpty() ? null : all.getFirst(), all);
    }

    public boolean isRecorded() {
        return emotion != null;
    }
}
