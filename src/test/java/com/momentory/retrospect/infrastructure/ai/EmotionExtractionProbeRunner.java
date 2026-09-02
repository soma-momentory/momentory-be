package com.momentory.retrospect.infrastructure.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;

import tools.jackson.databind.json.JsonMapper;

/**
 * 프로브 러너 — 대화 묶음을 실제 LLM 에 넣고 <b>채점기 입력 형식</b>으로 예측 JSON 을 뽑는다.
 *
 * <p>테스트가 아니라 실험 도구다. {@code PROBE_OUT} 이 없으면 아예 돌지 않으므로 일반
 * {@code check} 에는 영향이 없다({@code GeminiApiClientRealIntegrationTest} 와 같은 opt-in 방식).
 *
 * <pre>
 * PROBE_IN=eval/challenge/boundary-probes.txt \
 * PROBE_OUT=/tmp/pred.json PROBE_VARIANT=FEW_SHOT_COMBINED \
 *   ./gradlew cleanTest test --tests "*EmotionExtractionProbeRunner"
 * </pre>
 *
 * <p>⚠ {@code cleanTest} 를 붙여야 한다 — Gradle 은 환경변수를 입력으로 추적하지 않아 변형만 바꾸면
 * 테스트를 UP-TO-DATE 로 건너뛴다.
 *
 * <p>평가 조건대로 <b>temperature 0</b> 으로 부르고, 무료 티어 RPM 제한을 피해 호출 간격을 둔다 —
 * 간격 없이 몰아넣으면 조용히 빈 결과가 돌아온다(2026-09-02 확인: 35초에 24콜 → 9개 실패).
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PROBE_OUT", matches = ".+")
class EmotionExtractionProbeRunner {

    private static final long THROTTLE_MS = 4500;   // 약 13 req/min

    @Test
    void run() throws IOException {
        String in = System.getenv("PROBE_IN");
        String out = System.getenv("PROBE_OUT");
        String variant = System.getenv().getOrDefault("PROBE_VARIANT", "ZERO_SHOT");

        GeminiApiProperties props = new GeminiApiProperties(
                "https://generativelanguage.googleapis.com", System.getenv("GEMINI_API_KEY"),
                "gemini-flash-lite-latest", 0.0,
                Duration.ofSeconds(5), Duration.ofSeconds(60), "gemini-embedding-001");
        JsonMapper mapper = JsonMapper.builder().build();
        GeminiEmotionExtractor extractor = new GeminiEmotionExtractor(
                new GeminiApiClient(new GeminiApiClientConfiguration().geminiRestClient(props),
                        mapper, e -> { }, props),
                new PromptFactory(2000, EmotionPromptVariant.valueOf(variant)));

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String block : Files.readString(Path.of(in)).strip().split("\n\n")) {
            String[] lines = block.strip().split("\n");
            String[] head = lines[0].substring(1).split("\\|");
            RetrospectState state = new RetrospectState(head[0]);
            state.begin("없음".equals(head[1]) ? null : head[1], null, null, "정민", "취업");
            for (int i = 1; i < lines.length; i++) {
                String text = lines[i].substring(2);
                if (lines[i].startsWith("A:")) {
                    state.addAssistantMessage(text);
                } else {
                    state.addUserMessage(text);
                }
            }
            throttle();
            sessions.add(toScorerShape(head[0], extractor.extract(state)));
        }
        Files.writeString(Path.of(out), mapper.writeValueAsString(sessions));
    }

    private static Map<String, Object> toScorerShape(String sessionId, EmotionExtraction r) {
        List<Map<String, Object>> events = new ArrayList<>();
        r.events().forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("label", e.label());
            m.put("summary", e.summary());
            m.put("evidence", e.evidence());
            events.add(m);
        });
        List<Map<String, Object>> emotions = new ArrayList<>();
        r.emotions().forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", e.eventId());
            m.put("normalized", e.normalized() == null ? null : e.normalized().key());
            m.put("intensity", e.intensity());
            m.put("phase", e.phase() == null ? null : e.phase().key());
            m.put("evidenceIds", e.evidenceIds());
            emotions.add(m);
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sessionId", sessionId);
        s.put("events", events);
        s.put("emotions", emotions);
        return s;
    }

    private static void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
