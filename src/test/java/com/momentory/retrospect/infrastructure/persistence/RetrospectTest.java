package com.momentory.retrospect.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.RetrospectEndReason;
import com.momentory.retrospect.domain.RetrospectStatus;

class RetrospectTest {

    @Test
    @DisplayName("완료 시각과 종료 사유는 처음 끝난 때 한 번만 채운다 — 끝난 세션에 다시 동기화해도 덮어쓰지 않는다")
    void keepsTheFirstEnding() {
        Retrospect retrospect = Retrospect.start(1L, RetrospectStatus.IN_PROGRESS, null, "{}");
        retrospect.sync(RetrospectStatus.IN_PROGRESS, "{}", null, null);
        assertThat(retrospect.getCompletedAt()).isNull();
        assertThat(retrospect.getEndReason()).isNull();

        Instant first = Instant.parse("2026-11-17T10:00:00Z");
        retrospect.sync(RetrospectStatus.COMPLETED, "{}", first, RetrospectEndReason.WRAP_UP_ACCEPTED);
        retrospect.sync(RetrospectStatus.COMPLETED, "{}", first.plusSeconds(60), RetrospectEndReason.USER_REQUEST);

        assertThat(retrospect.getCompletedAt()).isEqualTo(first);
        assertThat(retrospect.getEndReason()).isEqualTo(RetrospectEndReason.WRAP_UP_ACCEPTED);
    }
}
