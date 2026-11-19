package com.momentory.letter.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.momentory.letter.application.LetterPictureProperties;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Plan;
import com.momentory.retrospect.application.metering.CallLog;
import com.momentory.retrospect.application.metering.LlmCallRecorded;
import com.momentory.retrospect.infrastructure.ai.GeminiApiProperties;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 그림일기 2단계 — 장면 설명과 기준 그림 세 장으로 말 없는 4컷을 그린다. 일기 원문은 보내지 않는다.
 *
 * <p>기준 그림은 일기 상세의 감정 그림(앱 {@code assets/diary/photos})에서 기쁨 · 지침 · 애정 세 장을 옮겨 왔다.
 * 그림 한 장 입력이 1,120토큰쯤이라 세 장을 보내도 약 1.2원이다 — 빼면 바바 얼굴이 흔들린다(2026-11-18 시험).
 * 이미지 응답은 {@link com.momentory.retrospect.infrastructure.ai.GeminiApiClient} 의 JSON 구조화 출력 길과 달라
 * 같은 RestClient 로 따로 부른다. 실패는 던지지 않고 빈 값으로 돌려준다.
 */
@Component
public class GeminiPictureDrawer {

    private static final Logger log = LoggerFactory.getLogger(GeminiPictureDrawer.class);
    private static final String GENERATE_PATH = "/v1beta/models/{model}:generateContent";
    static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final List<String> REFS = List.of("joy.jpg", "exhausted.jpg", "affection.jpg");

    static final String STYLE = """
            STYLE (most important): copy the art style of the attached reference images exactly.
            - Cute kawaii picture-book illustration. Thick, uniform dark-brown outlines on everything. Flat colour fills with a soft, subtle paper-grain texture. Gentle warm lighting only.
            - The character is Baba, a chubby capybara, identical to the references: warm orange-tan pear-shaped body, big round head, darker brown muzzle patch, small round brown ears, stubby brown paws with 2-3 short toe lines, round pink blush cheeks, a big darker-brown oval muzzle patch covering the nose and mouth (this patch is what makes Baba a capybara, never draw Baba as a bear), simple dot eyes or curved arc eyes, tiny Y-shaped nose and mouth.
            - Backgrounds are cozy and simple: soft pastel palette (sky blue, sage green, cream, blush pink, warm wood), rounded shapes, few details.
            - Baba is the only capybara and plays the diary writer. Other people in the day appear as other cute round animals in the same style (bear, rabbit, cat, duck, sheep...), so Baba is always easy to spot. Never draw humans.
            - Use the references ONLY for art style and Baba's look. Do not reuse their scenes, rooms, furniture layout or compositions; invent new scenes that fit this diary.
            - The image is purely pictorial, like a silent pantomime picture book: there is no writing of any kind anywhere. Signs, papers, screens and clocks are drawn as plain blank shapes. Emotions are shown only through faces, poses and small effect marks (tears, sweat drops, sparkles, steam puffs).""";

    /** 그린 그림 한 장 — 모델이 준 그대로(보통 PNG). */
    public record Drawn(byte[] bytes, String mimeType) {

        public Drawn {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Response(List<Candidate> candidates, Usage usageMetadata, String modelVersion) {
    }

    record Candidate(Content content) {
    }

    record Content(List<Part> parts) {
    }

    record Part(InlineData inlineData) {
    }

    record InlineData(String mimeType, String data) {
    }

    record Usage(Integer promptTokenCount, Integer candidatesTokenCount) {
    }

    private final RestClient restClient;
    private final GeminiApiProperties api;
    private final ApplicationEventPublisher events;
    private final String model;
    private final List<Map<String, Object>> references;

    public GeminiPictureDrawer(@Qualifier("geminiRestClient") RestClient restClient, GeminiApiProperties api,
            ApplicationEventPublisher events, LetterPictureProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(api.connectTimeout()).build());
        factory.setReadTimeout(TIMEOUT);
        this.restClient = restClient.mutate().requestFactory(factory).build();
        this.api = api;
        this.events = events;
        this.model = properties.imageModel();
        this.references = REFS.stream().map(GeminiPictureDrawer::reference).toList();
    }

    public Optional<Drawn> draw(Long letterId, Plan plan) {
        List<Object> parts = new ArrayList<>(references);
        parts.add(Map.of("text", prompt(plan)));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", Map.of(
                        "responseModalities", List.of("IMAGE"),
                        "imageConfig", Map.of("aspectRatio", "3:4")));
        long startedAt = System.nanoTime();
        try {
            Response response = restClient.post()
                    .uri(GENERATE_PATH, model)
                    .header("x-goog-api-key", api.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Response.class);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            Optional<Drawn> drawn = imageOf(response);
            if (drawn.isEmpty()) {
                log.warn("그림일기 응답에 그림이 없음 letter={} ({}ms)", letterId, elapsedMs);
                return Optional.empty();
            }
            Usage usage = response.usageMetadata();
            events.publishEvent(new LlmCallRecorded("letter-" + letterId, new CallLog(
                    LlmRole.LETTER_PICTURE_DRAW.key(),
                    response.modelVersion() == null || response.modelVersion().isBlank() ? model
                            : response.modelVersion(),
                    "letter-picture",
                    usage == null || usage.promptTokenCount() == null ? 0 : usage.promptTokenCount(),
                    usage == null || usage.candidatesTokenCount() == null ? 0 : usage.candidatesTokenCount(),
                    0, elapsedMs, false)));
            return drawn;
        } catch (RuntimeException e) {
            log.warn("그림일기 그리기 실패 letter={} ({}ms): {}", letterId,
                    (System.nanoTime() - startedAt) / 1_000_000L, e.toString());
            return Optional.empty();
        }
    }

    public String model() {
        return model;
    }

    static Optional<Drawn> imageOf(Response response) {
        if (response == null || response.candidates() == null) {
            return Optional.empty();
        }
        return response.candidates().stream()
                .filter(c -> c != null && c.content() != null && c.content().parts() != null)
                .flatMap(c -> c.content().parts().stream())
                .filter(p -> p != null && p.inlineData() != null && p.inlineData().data() != null
                        && p.inlineData().mimeType() != null && p.inlineData().mimeType().startsWith("image/"))
                .findFirst()
                .map(p -> new Drawn(Base64.getDecoder().decode(p.inlineData().data()), p.inlineData().mimeType()));
    }

    static String prompt(Plan plan) {
        String cast = plan.cast().isEmpty() ? "only Baba"
                : plan.cast().stream().map(c -> c.role() + " = a " + c.animal()).collect(Collectors.joining("; "));
        StringBuilder panels = new StringBuilder();
        for (int i = 0; i < plan.panels().size(); i++) {
            GeminiScenePlanner.Panel p = plan.panels().get(i);
            panels.append("Panel ").append(i + 1).append(": setting: ").append(p.setting())
                    .append(". Action: ").append(p.action())
                    .append(". Baba's face: ").append(p.babaExpression())
                    .append(". Others: ").append(p.others() == null || p.others().isBlank() ? "none" : p.others())
                    .append(".\n");
        }
        return "Draw a wordless silent 4-panel comic in a 2x2 grid with thin cream gutters and rounded panel corners, "
                + "read left-to-right, top-to-bottom. Baba must look like the exact same character in all four panels. "
                + "Portrait 3:4.\n\n"
                + "CAST: Baba the capybara; " + cast + ". Each cast member looks the same in every panel.\n\n"
                + panels + "\n" + STYLE;
    }

    private static Map<String, Object> reference(String name) {
        try (InputStream in = new ClassPathResource("letter-picture/refs/" + name).getInputStream()) {
            return Map.of("inline_data", Map.of("mime_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(in.readAllBytes())));
        } catch (IOException e) {
            throw new IllegalStateException("그림일기 기준 그림을 읽지 못했습니다: " + name, e);
        }
    }
}
