package com.momentory.diary.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.application.RetrospectCompleted;

/**
 * 회고 완료 이벤트를 받아 일기를 남기는 diary 컨텍스트의 진입점 — 쓰기 방향에서 retrospect 와의
 * 결합을 이벤트로 끊는다(retrospect 는 diary 를 모르고, diary 가 retrospect 의 이벤트를 구독한다).
 *
 * <p><b>{@code @EventListener} 는 발행 시점에 같은 스레드·같은 트랜잭션에서 동기 실행된다.</b>
 * 여기서 예외가 나면 회고 턴 트랜잭션째 롤백돼 일기 유실이 없다. 절대 {@code @TransactionalEventListener}
 * (AFTER_COMMIT) 로 바꾸지 말 것 — 커밋 뒤 실행이라 저장 실패 시 "일기 없는 완료 회고"가 생긴다
 * (그 방식으로 가려면 outbox 가 함께 와야 한다).
 *
 * <p>회고 한 벌에 일기 하나라, 이미 있으면 만들지 않는다(완료 턴에 한 번만 생긴다).
 */
@Component
public class DiaryFromRetrospectListener {

    private final DiaryRepository diaryRepository;

    public DiaryFromRetrospectListener(DiaryRepository diaryRepository) {
        this.diaryRepository = diaryRepository;
    }

    @EventListener
    public void on(RetrospectCompleted event) {
        RetrospectCompleted.DiaryData diary = event.diary();
        if (diary == null || diaryRepository.existsByRetrospectId(event.retrospectId())) {
            return;
        }
        diaryRepository.save(Diary.create(event.userId(), event.retrospectId(),
                diary.original(), diary.reframed(), diary.currentEmotion(),
                diary.scheduleEmotion()));
    }
}
