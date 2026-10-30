package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.momentory.retrospect.domain.RetrospectState;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/** 가상 대화로만 일기의 분량과 문단을 확인한다. 실제 사용자 기록은 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiDiaryWriterRealIntegrationTest {
    @Test
    void twoDistinctExperiencesBecomeFiveToSixSentencesInContextParagraphs() {
        var state = new RetrospectState("diary-paragraph-smoke");
        state.addUserMessage("오전에는 회사에서 준비한 발표를 했어요. 중간에 말이 막혔지만 끝까지 발표했어요.");
        state.addAssistantMessage("그때 어떤 기분이었어요?");
        state.addUserMessage("처음엔 긴장했는데 끝내고 나서는 뿌듯했어요.");
        state.addAssistantMessage("다른 기억에 남는 일이 있었나요?");
        state.addUserMessage("퇴근 후에는 공원에서 친구와 산책했어요. 오랜만에 만나서 반가웠고 같이 걸으니 마음이 편안했어요.");

        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var restClient = new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP);
        var client = new GeminiApiClient(restClient, JsonMapper.builder().build(), event -> { }, properties);
        var writer = new GeminiDiaryWriter(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));

        String diary = writer.write(state).orElseThrow().diary();
        assertThat(diary).contains("발표", "산책");
        long sentences = Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(diary).results().count();
        assertThat(sentences).isBetween(5L, 6L);
        String[] paragraphs = diary.strip().split("\\R\\s*\\R");
        assertThat(paragraphs).hasSize(2);
        assertThat(Arrays.stream(paragraphs).allMatch(p ->
                Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(p).results().count() >= 2)).isTrue();
    }
}
