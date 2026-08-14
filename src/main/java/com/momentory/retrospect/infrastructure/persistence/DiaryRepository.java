package com.momentory.retrospect.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRepository extends JpaRepository<Diary, Long> {

    /** 그날의 회고에 딸린 일기 — 회고 한 벌에 하나뿐이다. */
    Optional<Diary> findByRetrospectId(Long retrospectId);

    boolean existsByRetrospectId(Long retrospectId);
}
