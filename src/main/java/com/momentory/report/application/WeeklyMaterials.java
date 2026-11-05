package com.momentory.report.application;

import java.time.LocalDate;
import java.util.List;

import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeeklyPages;

/** 한 주를 세고 쓰는 데 드는 재료 한 벌. 트랜잭션 안에서 모아 두고, AI 호출은 그 밖에서 한다. */
record WeeklyMaterials(Long userId, LocalDate weekStart, LocalDate weekEnd, String nickname,
        List<DayStory> stories, List<WeeklyPages.Task> tasks, List<WeeklyPages.Rest> rests,
        List<WeeklyPages.Rest> restsSoFar) {
}
