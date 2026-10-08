package com.momentory.restrecord.application;

import com.momentory.common.time.DayBoundary;
import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.restrecord.domain.RestRecord;
import com.momentory.restrecord.infrastructure.RestRecordRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RestRecordService {

    private final RestRecordRepository restRecordRepository;
    private final UserRepository userRepository;

    public RestRecordService(RestRecordRepository restRecordRepository, UserRepository userRepository) {
        this.restRecordRepository = restRecordRepository;
        this.userRepository = userRepository;
    }

    /** 남긴 순간이 속한 하루(04:00 경계)에 이용 기록 하나를 더한다. */
    @Transactional
    public RestRecordResult addRecord(Long userId, RestContent content, boolean completed, RestMood mood) {
        requireUser(userId);
        RestRecord saved = restRecordRepository.saveAndFlush(RestRecord.create(userId, content, DayBoundary.today(), completed, mood));
        return RestRecordResult.from(saved);
    }

    // ponytail: 페이지 없이 전부 돌려준다 — 쉼터 하나에 하루 몇 번이라 몇 년치도 작다. 느려지면 커서 페이지로 바꾼다
    /** 그 쉼터의 이용 기록 — 최신순. */
    @Transactional(readOnly = true)
    public List<RestRecordResult> getRecords(Long userId, RestContent content) {
        requireUser(userId);
        return restRecordRepository.findByUserIdAndContentOrderByCreatedAtDescIdDesc(userId, content).stream()
                .map(RestRecordResult::from)
                .toList();
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
