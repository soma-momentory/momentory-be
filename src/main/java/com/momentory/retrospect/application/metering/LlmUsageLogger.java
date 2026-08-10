package com.momentory.retrospect.application.metering;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * LLM 사용량 로거 — {@link LlmCallRecorded} 이벤트를 구조화 로그로 남긴다.
 *
 * <p>예전엔 세션별로 인메모리에 쌓아 두고 {@code GET /usage} 로 요약을 노출했지만, 재시작 시
 * 사라지고 소비처도 없어 로그로 전환했다. 세션 누적·역할별 비용·풀 대체율 같은 집계는 로그
 * 파이프라인에서 {@code llm-usage} 로거를 필터해 처리한다.
 *
 * <p>LLM 어댑터는 무상태 싱글턴이라 세션별 로그를 들고 있을 수 없다. 이벤트로 던지고 여기서 찍는다.
 */
@Component
public class LlmUsageLogger {

    private static final Logger log = LoggerFactory.getLogger("llm-usage");

    private final double pricePerMillionIn;
    private final double pricePerMillionOut;

    public LlmUsageLogger(
            // flash-lite 공개 요율(USD / 1M tokens, 근사값). 실제 청구와 다를 수 있다.
            @Value("${momentory.price.in-per-million:0.10}") double pricePerMillionIn,
            @Value("${momentory.price.out-per-million:0.40}") double pricePerMillionOut) {
        this.pricePerMillionIn = pricePerMillionIn;
        this.pricePerMillionOut = pricePerMillionOut;
    }

    @EventListener
    public void on(LlmCallRecorded event) {
        CallLog l = event.log();
        if (l.poolUsed()) {
            // 질문 풀이 AI 를 대체한 턴 — 토큰·비용 0. 풀 대체율 집계용.
            log.info("session={} role={} phase={} pool=true", event.sessionId(), l.role(),
                    l.phase());
            return;
        }
        double costUsd = (l.inTokens() / 1_000_000.0) * pricePerMillionIn
                + (l.outTokens() / 1_000_000.0) * pricePerMillionOut;
        log.info(
                "session={} role={} model={} phase={} in={} out={} cached={} latencyMs={} "
                        + "costUsd={} pool=false",
                event.sessionId(), l.role(), l.model(), l.phase(), l.inTokens(), l.outTokens(),
                l.cachedTokens(), l.latencyMs(), String.format(Locale.ROOT, "%.6f", costUsd));
    }

    /** 질문 풀이 AI 를 대체한 턴. 이벤트가 아니라 직접 기록한다 — LLM 호출이 아니기 때문. */
    public void recordPoolSubstitution(String sessionId, String role, String phase) {
        on(new LlmCallRecorded(sessionId, CallLog.poolSubstitution(role, phase)));
    }
}
