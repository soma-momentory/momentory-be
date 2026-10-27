package com.momentory.letter.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.domain.LetterRules;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.infrastructure.RetrospectDomainConfiguration;
import com.momentory.retrospect.infrastructure.ai.GeminiApiClient;
import com.momentory.retrospect.infrastructure.ai.GeminiApiProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Gemini 로 편지 세 호출(본문 · 욕구 · 작은 일)의 지연과 출력 모양을 본다. {@code GEMINI_API_KEY}
 * 가 있을 때만 돈다. 재료는 지어낸 일기라 실제 사용자 글을 보내지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLetterWriterRealIntegrationTest {

    private static final List<LetterMaterials> SAMPLES = List.of(
            sample(1, "오늘은 팀 발표가 있었다. 시작 전에 손이 계속 떨렸지만 끝까지 말했다. 끝나고 동료가 잘했다고 해 줘서 조금 놓였다.",
                    List.of("발표 전에 손이 계속 떨렸어요", "그래도 끝까지 말했어요", "동료가 잘했다고 해 줘서 좀 놓였어요"),
                    List.of("팀 발표 (했음)")),
            sample(2, "퇴근하고 집에 오니 너무 지쳐서 아무것도 못 했다. 저녁도 대충 먹었다. 내일은 좀 일찍 자고 싶다.",
                    List.of("그냥 너무 피곤했어요", "저녁도 대충 먹었어요", "내일은 일찍 자고 싶어요"),
                    List.of("운동 가기 (안 했음)", "장보기 (했음)")),
            sample(3, "오랜만에 친구를 만나 카페에서 이야기를 오래 했다. 웃을 일이 많아서 하루가 가볍게 느껴졌다.",
                    List.of("친구랑 오랜만에 수다 떨었어요", "웃을 일이 많아서 좋았어요"),
                    List.of()));

    @Test
    void measuresLetterCallsAgainstRealGemini() throws Exception {
        GeminiApiProperties properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7, Duration.ofSeconds(3),
                Duration.ofSeconds(30), "text-embedding-004");
        RestClient restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        NeedActionCatalog catalog = new RetrospectDomainConfiguration().needActionCatalog(JsonMapper.builder().build());
        GeminiLetterWriter writer = new GeminiLetterWriter(
                new GeminiApiClient(restClient, JsonMapper.builder().build(), e -> { }, properties), catalog,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        for (LetterMaterials m : SAMPLES) {
            long t0 = System.nanoTime();
            Optional<GeminiLetterWriter.Body> body = writer.writeBody(m);
            long bodyMs = ms(t0);
            long t1 = System.nanoTime();
            Optional<GeminiLetterWriter.Body> onOpen = writer.writeBodyOnOpen(m);
            long openMs = ms(t1);
            long t2 = System.nanoTime();
            List<Need> needs = writer.pickNeeds(m);
            long needsMs = ms(t2);
            long t3 = System.nanoTime();
            Optional<List<String>> ids = writer.pickActionIds(m, needs.isEmpty() ? List.of() : needs);
            long actionsMs = ms(t3);

            assertThat(body).isPresent();
            String quote = body.get().quote();
            System.out.printf("letter-real sample=%d bodyMs=%d openMs=%d needsMs=%d actionsMs=%d "
                            + "quoteVerbatim=%s needs=%s picked=%s%n  quote=%s%n  insight=%s%n  tonight=%s%n",
                    m.letterId(), bodyMs, openMs, needsMs, actionsMs,
                    LetterRules.isVerbatim(quote, m.userWords()), needs.stream().map(Need::word).toList(),
                    ids.map(i -> catalog.selectedActions(needs, i).stream().map(NeedActionCatalog.Action::title)
                            .toList()).orElse(null),
                    quote, body.get().insight(), body.get().tonightQuestion());
            assertThat(onOpen).isPresent();
        }
    }

    private static LetterMaterials sample(long id, String diary, List<String> words, List<String> schedules) {
        return new LetterMaterials(id, LocalDate.of(2026, 10, 26), "하늘", diary, words, false, List.of(), null,
                schedules, List.of(), null);
    }

    private static long ms(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
