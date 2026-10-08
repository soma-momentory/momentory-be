package com.momentory.treasure.infrastructure;

import com.momentory.treasure.domain.Treasure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TreasureRepository extends JpaRepository<Treasure, Long> {

    List<Treasure> findByUserIdAndTreasureDateOrderByCreatedAtAscIdAsc(Long userId, LocalDate treasureDate);

    List<Treasure> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
