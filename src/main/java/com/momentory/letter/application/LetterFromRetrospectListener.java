package com.momentory.letter.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.application.DiaryOriginalUpdated;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.retrospect.application.RetrospectCompleted;

/**
 * 회고 완료 · 일기 고침을 받아 편지를 만든다(PRD 6-1 · 6-2).
 *
 * <p><b>할 일은 같은 트랜잭션에서, AI 생성은 커밋 뒤에.</b> PENDING 행은 회고 트랜잭션이 커밋되기
 * 직전(BEFORE_COMMIT)에 넣는다 — 그 시점엔 일기 리스너({@code DiaryFromRetrospectListener} ·
 * 동기 {@code @EventListener})가 이미 일기를 저장했고, 행 넣기가 실패하면 회고 턴째 롤백된다.
 * 생성은 커밋 뒤(AFTER_COMMIT) 편지 실행기로 넘긴다. 생성 실패가 일기를 되돌리면 안 되고, 커밋
 * 전에는 다른 스레드에서 일기가 아직 보이지 않기 때문이다.
 */
@Component
public class LetterFromRetrospectListener {

    private final DiaryRepository diaryRepository;
    private final LetterRepository letterRepository;
    private final LetterGenerator generator;
    private final LetterTaskLauncher launcher;

    public LetterFromRetrospectListener(DiaryRepository diaryRepository,
            LetterRepository letterRepository, LetterGenerator generator, LetterTaskLauncher launcher) {
        this.diaryRepository = diaryRepository;
        this.letterRepository = letterRepository;
        this.generator = generator;
        this.launcher = launcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void createPending(RetrospectCompleted event) {
        if (event.diary() == null) {
            return;
        }
        diaryRepository.findByRetrospectId(event.retrospectId()).ifPresent(diary ->
                letterRepository.insertPending(diary.getUserId(), diary.getId(),
                        DayBoundary.toLocalDate(diary.getCreatedAt())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void generate(RetrospectCompleted event) {
        if (event.diary() == null) {
            return;
        }
        Long retrospectId = event.retrospectId();
        launcher.launch(() -> diaryRepository.findByRetrospectId(retrospectId)
                .ifPresent(diary -> generator.generateForDiary(diary.getId())));
    }

    /** 일기를 고친 트랜잭션 안에서 표시한다 — 이미 열어 본 편지는 그대로 둔다. */
    @EventListener
    public void markStale(DiaryOriginalUpdated event) {
        letterRepository.markStale(event.diaryId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void regenerate(DiaryOriginalUpdated event) {
        Long diaryId = event.diaryId();
        launcher.launch(() -> generator.generateForDiary(diaryId));
    }
}
