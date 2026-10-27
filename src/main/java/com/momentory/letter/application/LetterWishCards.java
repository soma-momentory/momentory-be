package com.momentory.letter.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;

/** 편지의 출처 일기에 딸린 바람카드 — 일기 → 회고 → 카드(회고 한 벌에 한 장). 없으면 empty. */
@Component
class LetterWishCards {

    private final DiaryRepository diaryRepository;
    private final ActionCardRepository actionCardRepository;

    LetterWishCards(DiaryRepository diaryRepository, ActionCardRepository actionCardRepository) {
        this.diaryRepository = diaryRepository;
        this.actionCardRepository = actionCardRepository;
    }

    /**
     * 편지에서 고른 일을 해 봤는가 — 바람카드가 있으면 카드의 완료 표시만, 없으면 밤 회고 답만 본다
     * (편지 PRD 14-4 · 원본은 카드). 쉼터에서 나중에 체크하거나 취소한 것도 그대로 따른다.
     */
    boolean tried(Letter letter, LetterAction action) {
        return of(letter).map(ActionCard::isDone).orElseGet(action::isTried);
    }

    Optional<ActionCard> of(Letter letter) {
        return diaryRepository.findById(letter.getDiaryId())
                .flatMap(diary -> actionCardRepository.findByRetrospectId(diary.getRetrospectId()));
    }
}
