package com.momentory.retrospect.application.metering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link LlmUsageLogger} 가 호출·풀 대체를 {@code llm-usage} 로거로 남기는지 검증한다.
 *
 * <p>집계(세션 누적·역할별·풀 대체율)는 로그 파이프라인의 몫이라 여기선 한 줄 한 줄 잘 찍히는지,
 * 특히 유료 호출 비용이 요율대로 계산되는지만 본다.
 */
class LlmUsageLoggerTest {

    private final LlmUsageLogger logger = new LlmUsageLogger(0.10, 0.40);
    private final Logger usageLog = (Logger) LoggerFactory.getLogger("llm-usage");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        usageLog.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        usageLog.detachAppender(appender);
    }

    private String lastMessage() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    @Test
    @DisplayName("유료 호출 — 토큰·지연·추정 비용을 요율대로 찍는다")
    void logsPaidCall() {
        // in 1M * $0.10 + out 1M * $0.40 = $0.50
        logger.on(new LlmCallRecorded("s1",
                new CallLog("AI-G2", "gemini-flash-lite-latest", "common",
                        1_000_000, 1_000_000, 0, 500, false)));

        assertThat(lastMessage())
                .contains("session=s1", "role=AI-G2", "in=1000000", "out=1000000",
                        "latencyMs=500", "costUsd=0.500000", "pool=false");
    }

    @Test
    @DisplayName("질문 풀 대체 — 토큰·비용 없이 pool=true 로만 찍는다")
    void logsPoolSubstitution() {
        logger.recordPoolSubstitution("s1", "AI-G2-fallback", "common");

        assertThat(lastMessage())
                .contains("session=s1", "role=AI-G2-fallback", "phase=common", "pool=true")
                .doesNotContain("costUsd");
    }
}
