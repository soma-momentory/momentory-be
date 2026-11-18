package com.momentory.letter.infrastructure;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.letter.domain.LetterPicture;
import com.momentory.letter.domain.LetterPictureStatus;

public interface LetterPictureRepository extends JpaRepository<LetterPicture, Long> {

    /**
     * 그리기에 들어간다 — 같은 편지에 두 번 와도 한쪽만 1을 받는다. 행이 이미 있으면(다 그림 · 실패 · 그리는 중)
     * 다시 그리지 않는다. 일기를 고쳐 편지를 다시 만들어도 그림은 그대로 둔다(2026-11-18 결정 · 비용).
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into letter_pictures (letter_id, status, prompt_version, started_at, created_at, updated_at)
            values (:letterId, 'GENERATING', :promptVersion, :now, :now, :now)
            on conflict (letter_id) do nothing
            """, nativeQuery = true)
    int claim(@Param("letterId") Long letterId, @Param("promptVersion") String promptVersion,
            @Param("now") Instant now);

    boolean existsByLetterIdAndStatus(Long letterId, LetterPictureStatus status);
}
