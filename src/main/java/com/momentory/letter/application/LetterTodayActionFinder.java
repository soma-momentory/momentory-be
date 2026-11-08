package com.momentory.letter.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.common.time.DayBoundary;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.retrospect.application.TodayActionFinder;
import com.momentory.retrospect.domain.TodayAction;

/**
 * 오늘(04:00 경계) 받은 편지 — 어제 일기의 편지 — 에서 고른 작은 일을 회고에 넘긴다. 바람카드가 이미
 * 「했어요」면 회고는 「해 봤네요」로 시작한다(편지 PRD 14-4). 카드가 없는 날은 편지의 답만 본다.
 */
@Component
class LetterTodayActionFinder implements TodayActionFinder {

    private final LetterRepository letterRepository;
    private final LetterActionRepository letterActionRepository;
    private final LetterWishCards wishCards;

    LetterTodayActionFinder(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, LetterWishCards wishCards) {
        this.letterRepository = letterRepository;
        this.letterActionRepository = letterActionRepository;
        this.wishCards = wishCards;
    }

    @Override
    public Optional<TodayAction> findFor(Long userId) {
        return letterRepository.findByUserIdAndLetterDate(userId, DayBoundary.today().minusDays(1))
                .flatMap(letter -> letterActionRepository.findByLetterIdAndChosenTrue(letter.getId())
                        .map(action -> TodayAction.of(action.getId(), action.getTitle(),
                                wishCards.tried(letter, action))));
    }
}
