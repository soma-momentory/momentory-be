package com.momentory.retrospect.application.metering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 세션별 LLM 사용량 수집기 — {@link LlmCallRecorded} 이벤트 리스너.
 *
 * <p>LLM 어댑터는 싱글턴이라 세션별 로그를 들고 있을 수 없다. 이벤트로 던지고 여기서 모은다.
 *
 * <p>인메모리다 — 이 앱은 인증·DB 없는 프로토타입이라 그걸로 충분하다.
 */
@Component
public class UsageRecorder {

    private final Map<String, List<CallLog>> logsBySession = new ConcurrentHashMap<>();
    private final String model;
    private final double pricePerMillionIn;
    private final double pricePerMillionOut;

    public UsageRecorder(
            @Value("${gemini.api.model:gemini-flash-lite-latest}")
            String model,
            // flash-lite 공개 요율(USD / 1M tokens, 근사값). 실제 청구와 다를 수 있다.
            @Value("${momentory.price.in-per-million:0.10}") double pricePerMillionIn,
            @Value("${momentory.price.out-per-million:0.40}") double pricePerMillionOut) {
        this.model = model;
        this.pricePerMillionIn = pricePerMillionIn;
        this.pricePerMillionOut = pricePerMillionOut;
    }

    @EventListener
    public void on(LlmCallRecorded event) {
        logsBySession
                .computeIfAbsent(event.sessionId(), k -> new CopyOnWriteArrayList<>())
                .add(event.log());
    }

    /** 질문 풀이 AI 를 대체한 턴. 이벤트가 아니라 직접 기록한다 — LLM 호출이 아니기 때문. */
    public void recordPoolSubstitution(String sessionId, String role, String phase) {
        on(new LlmCallRecorded(sessionId, CallLog.poolSubstitution(role, phase)));
    }

    public List<CallLog> logsOf(String sessionId) {
        return List.copyOf(logsBySession.getOrDefault(sessionId, List.of()));
    }

    public void forget(String sessionId) {
        logsBySession.remove(sessionId);
    }

    /** 세션 하나의 사용량 집계 (원본 {@code usage_summary()}). */
    public UsageSummary summarize(String sessionId) {
        List<CallLog> logs = logsOf(sessionId);
        if (logs.isEmpty()) {
            return UsageSummary.empty(model);
        }

        List<CallLog> paid = logs.stream().filter(l -> !l.poolUsed()).toList();
        int poolCount = logs.size() - paid.size();

        Map<String, UsageSummary.RoleUsage> byRole = new LinkedHashMap<>();
        for (CallLog l : paid) {
            byRole.merge(l.role(),
                    new UsageSummary.RoleUsage(1, l.inTokens(), l.outTokens(), l.cachedTokens(),
                            l.latencyMs()),
                    (a, b) -> new UsageSummary.RoleUsage(
                            a.calls() + b.calls(),
                            a.inTokens() + b.inTokens(),
                            a.outTokens() + b.outTokens(),
                            a.cachedTokens() + b.cachedTokens(),
                            a.latencyMs() + b.latencyMs()));
        }

        int in = paid.stream().mapToInt(CallLog::inTokens).sum();
        int out = paid.stream().mapToInt(CallLog::outTokens).sum();
        int cached = paid.stream().mapToInt(CallLog::cachedTokens).sum();
        long latency = paid.stream().mapToLong(CallLog::latencyMs).sum();
        double cost = (in / 1_000_000.0) * pricePerMillionIn
                + (out / 1_000_000.0) * pricePerMillionOut;

        return new UsageSummary(model, paid.size(), poolCount, in, out, cached, latency,
                Map.copyOf(byRole), cost, new ArrayList<>(logs));
    }
}
