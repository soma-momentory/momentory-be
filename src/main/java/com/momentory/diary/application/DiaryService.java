package com.momentory.diary.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;

/**
 * 일기 쓰기 유스케이스 — 삭제. 조회는 {@link DiaryQueryService}, 생성은 회고 완료 흐름
 * ({@link DiaryFromRetrospectListener}) 이 맡는다.
 */
@Service
public class DiaryService {

    private final DiaryRepository diaryRepository;

    public DiaryService(DiaryRepository diaryRepository) {
        this.diaryRepository = diaryRepository;
    }

    /**
     * 일기 한 벌을 지운다 — 소유권을 함께 검증한다.
     *
     * <p><b>일기만 지운다.</b> 행동 카드는 자기 보관함(쉼터)에 날짜로 독립해 남고, 회고 세션도
     * 남긴다 — "회고 한 벌을 함께 지운다"는 상류 스냅샷과의 차이는 프론트
     * {@code docs/PRODUCT.md} 「상류와 차이」에 기록돼 있다.
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Diary diary = diaryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(DiaryNotFoundException::new);
        diaryRepository.delete(diary);
    }

    /**
     * 회고 완료 화면(C6)의 직접 고치기 — 그 회고의 일기 <b>본문만</b> 사용자 것으로 바꾼다.
     *
     * <p>회고로 만든 일기는 이미 서버에 있고(LLM 원본), 클라이언트는 일기의 id 가 아니라 회고 세션
     * id({@code retrospectId})를 들고 있으므로 그 열쇠로 찾는다. 회고 한 벌에 일기 하나라 유일하다.
     * 소유권은 조회한 일기의 {@code userId} 로 검증한다(남의 회고면 없는 것처럼 404).
     */
    @Transactional
    public DiaryView updateOriginalByRetrospect(Long userId, Long retrospectId, String original) {
        Diary diary = diaryRepository.findByRetrospectId(retrospectId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(DiaryNotFoundException::new);
        diary.updateOriginal(original);
        return DiaryView.from(diary);
    }
}
