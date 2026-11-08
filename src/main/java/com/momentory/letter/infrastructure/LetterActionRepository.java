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

    /** 칸을 읽지 않고 편지 id 만 — 편지 행을 잠근 뒤에 칸을 새로 읽기 위해서다(1차 캐시에 남지 않게). */
    @Query("select a.letterId from LetterAction a where a.id = :id")
    Optional<Long> findLetterIdById(@Param("id") Long id);

    /** 다시 만들 때 옛 칸을 먼저 지운다 — 같은 (편지, 칸) 유일 제약에 새 행이 걸리지 않게 바로 실행한다. */
    @Modifying
    @Query("delete from LetterAction a where a.letterId = :letterId")
    int deleteByLetterId(@Param("letterId") Long letterId);
}
