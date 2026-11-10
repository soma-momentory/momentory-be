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
    void twoDistinctExperiencesBecomeAtLeastFiveSentencesInContextParagraphs() {
        var state = new RetrospectState("diary-paragraph-smoke");
        state.addUserMessage("오전에는 회사에서 준비한 발표를 했어요. 중간에 말이 막혔지만 끝까지 발표했어요.");
        state.addAssistantMessage("그때 어떤 기분이었어요?");
        state.addUserMessage("처음엔 긴장했는데 끝내고 나서는 뿌듯했어요.");
        state.addAssistantMessage("다른 기억에 남는 일이 있었나요?");
        state.addUserMessage("퇴근 후에는 공원에서 친구와 산책했어요. 오랜만에 만나서 반가웠고 같이 걸으니 마음이 편안했어요.");

        String diary = writer().write(state).orElseThrow().diary();
        assertThat(diary).contains("발표", "산책");
        long sentences = Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(diary).results().count();
        assertThat(sentences).isBetween(5L, 9L);
        String[] paragraphs = diary.strip().split("\\R\\s*\\R");
        assertThat(paragraphs).hasSize(2);
        assertThat(Arrays.stream(paragraphs).allMatch(p ->
                Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(p).results().count() >= 2)).isTrue();
        assertThat(diary).doesNotContain("\n\n\n");
        var lines = diary.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize((int) sentences);
        assertThat(lines).allSatisfy(line ->
                assertThat(Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(line).results().count())
                        .as("각 줄은 한 문장이어야 한다").isEqualTo(1));
    }

    /** 사용자가 짧게만 답하고 바바가 공감만 한 대화도, 한 말을 빼지 않고 5문장 이상으로 풀어 써야 한다. */
    @Test
    void shortRepliesWithEmpathyOnlyStillKeepEveryStatementInAtLeastFiveSentences() {
        var state = new RetrospectState("diary-short-replies-smoke");
        state.addAssistantMessage("오늘 하루 중 가장 기억에 남는 일은 뭐였어요?");
        state.addUserMessage("보드게임 카페 재밌었고, 오랜만에 동기들 만나서 좋았어");
        state.addAssistantMessage("오랜만에 동기분들과 즐거운 시간을 보내셨군요.");
        state.addUserMessage("근데 집에 오니까 괜히 허전하다.. 내일 아침 수업 일찍 갈 수 있을까?");
        state.addAssistantMessage("즐거운 시간 뒤에 내일 걱정이 찾아와 마음이 허전하셨군요.");
        state.addUserMessage("자격증 공부 시작했는데 집중이 잘 안 돼 꼭 붙어야 하는데 ㅠㅠ");
        state.addAssistantMessage("꼭 이루고 싶은 목표라서 더 조급하게 느껴지시는군요.");
        state.addUserMessage("별로 절실하지 않은가 봐 이러는 거 보니");
        state.addAssistantMessage("스스로를 탓하지 않으셨으면 좋겠어요.");
        state.addUserMessage("고마워..");

        String diary = writer().write(state).orElseThrow().diary();
        long sentences = Pattern.compile("[.!?。！？]+(?=\\s|$)").matcher(diary).results().count();
        assertThat(sentences).as(diary).isGreaterThanOrEqualTo(5L);
        assertThat(diary).as(diary).contains("보드게임", "아침", "절실");
    }

    private static GeminiDiaryWriter writer() {
        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var restClient = new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP);
        var client = new GeminiApiClient(restClient, JsonMapper.builder().build(), event -> { }, properties);
        return new GeminiDiaryWriter(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));
    }
}
