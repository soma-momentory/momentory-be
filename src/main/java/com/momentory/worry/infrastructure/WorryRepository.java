package com.momentory.worry.infrastructure;

import com.momentory.worry.domain.Worry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorryRepository extends JpaRepository<Worry, Long> {

    List<Worry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
