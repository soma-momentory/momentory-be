package com.momentory.retrospect.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RetrospectRepository extends JpaRepository<Retrospect, Long> {

    /** 소유권을 함께 검증하는 조회 — 남의 세션을 못 열도록. */
    Optional<Retrospect> findByIdAndUserId(Long id, Long userId);

    List<Retrospect> findByUserIdOrderByCreatedAtDesc(Long userId);
}
