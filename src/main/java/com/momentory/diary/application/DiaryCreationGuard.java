package com.momentory.diary.application;

import org.springframework.stereotype.Component;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.application.AlreadyRetrospectedTodayException;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;

/** 두 작성 방식이 공유하는 하루 한 장 규칙. 호출자의 트랜잭션에서 잠금을 유지한다. */
@Component
public class DiaryCreationGuard {
    private final UserRepository users;
    private final DiaryRepository diaries;

    public DiaryCreationGuard(UserRepository users, DiaryRepository diaries) {
        this.users = users;
        this.diaries = diaries;
    }

    public void requireAvailable(Long userId) {
        users.findForDiaryCreation(userId).orElseThrow(AuthenticatedUserNotFoundException::new);
        var date = DayBoundary.today();
        if (diaries.existsByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId,
                DayBoundary.startOfDay(date), DayBoundary.startOfDay(date.plusDays(1)))) {
            throw new AlreadyRetrospectedTodayException();
        }
    }
}
