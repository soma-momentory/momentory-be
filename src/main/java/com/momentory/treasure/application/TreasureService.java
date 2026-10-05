package com.momentory.treasure.application;

import com.momentory.common.time.DayBoundary;
import com.momentory.treasure.domain.Treasure;
import com.momentory.treasure.infrastructure.TreasureRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class TreasureService {

    private final TreasureRepository treasureRepository;
    private final UserRepository userRepository;

    public TreasureService(TreasureRepository treasureRepository, UserRepository userRepository) {
        this.treasureRepository = treasureRepository;
        this.userRepository = userRepository;
    }

    /** 담은 순간이 속한 하루(04:00 경계)에 보물 하나를 더한다. */
    @Transactional
    public TreasureResult addTreasure(Long userId, String content) {
        requireUser(userId);
        Treasure saved = treasureRepository.saveAndFlush(Treasure.create(userId, DayBoundary.today(), content));
        return TreasureResult.from(saved);
    }

    /** 그 하루의 보물 — 담은 순서대로. */
    @Transactional(readOnly = true)
    public List<TreasureResult> getTreasuresOn(Long userId, LocalDate date) {
        requireUser(userId);
        return treasureRepository.findByUserIdAndTreasureDateOrderByCreatedAtAscIdAsc(userId, date).stream()
                .map(TreasureResult::from)
                .toList();
    }

    // ponytail: 페이지 없이 전부 돌려준다 — 하루 몇 줄이라 몇 년치도 작다. 느려지면 커서 페이지로 바꾼다
    /** 모든 보물 — 최신순. */
    @Transactional(readOnly = true)
    public List<TreasureResult> getAllTreasures(Long userId) {
        requireUser(userId);
        return treasureRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(TreasureResult::from)
                .toList();
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
