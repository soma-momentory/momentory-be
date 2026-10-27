package com.momentory.letter.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.momentory.letter.domain.LetterAction;

public interface LetterActionRepository extends JpaRepository<LetterAction, Long> {

    List<LetterAction> findByLetterIdOrderByPositionAsc(Long letterId);

    Optional<LetterAction> findByLetterIdAndChosenTrue(Long letterId);

    /** 다시 만들 때 옛 칸을 먼저 지운다 — 같은 (편지, 칸) 유일 제약에 새 행이 걸리지 않게 바로 실행한다. */
    @Modifying
    @Query("delete from LetterAction a where a.letterId = :letterId")
    int deleteByLetterId(@Param("letterId") Long letterId);
}
