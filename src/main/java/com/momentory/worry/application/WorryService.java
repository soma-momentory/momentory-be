package com.momentory.worry.application;

import com.momentory.common.time.DayBoundary;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.worry.domain.Worry;
import com.momentory.worry.infrastructure.WorryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorryService {

    private final WorryRepository worryRepository;
    private final UserRepository userRepository;

    public WorryService(WorryRepository worryRepository, UserRepository userRepository) {
        this.worryRepository = worryRepository;
        this.userRepository = userRepository;
    }

    /** 맡긴 순간이 속한 하루(04:00 경계)에 걱정 하나를 맡겨 둔다. */
    @Transactional
    public WorryResult keepWorry(Long userId, String content) {
        requireUser(userId);
        Worry saved = worryRepository.saveAndFlush(Worry.create(userId, DayBoundary.today(), content));
        return WorryResult.from(saved);
    }

    // ponytail: 페이지 없이 전부 돌려준다 — 보물과 같은 판단. 느려지면 커서 페이지로 바꾼다
    /** 맡겨둔 걱정 전부 — 최신순. */
    @Transactional(readOnly = true)
    public List<WorryResult> getWorries(Long userId) {
        requireUser(userId);
        return worryRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(WorryResult::from)
                .toList();
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
