package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;

class GeminiDiaryWriterTest {
    private final GeminiApiClient client = mock(GeminiApiClient.class);
    private final GeminiDiaryWriter writer = new GeminiDiaryWriter(client,
            new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));
    private final RetrospectState state = new RetrospectState("diary-count");

    @BeforeEach
    void setUp() {
        when(client.withReadTimeout(GeminiDiaryWriter.REVISION_TIMEOUT)).thenReturn(client);
    }

    @Test
    void fiveSentencesAndContextParagraphsAreReturnedWithoutAnotherCall() {
        var diary = new DiaryOutput("발표했다. 긴장했다. 마쳤다.\n\n산책했다. 편안했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(diary);
        verify(client).generate(any(), eq(DiaryOutput.class));
    }

    @Test
    void compressedDraftIsRevisedOnceUsingOriginalConversationAndWithoutInventingFacts() {
        state.addUserMessage("발표 때 긴장했지만 마쳤어요. 퇴근 후 산책하니 편안했어요.");
        var compressed = new DiaryOutput("발표하고 긴장했다. 마쳤다. 산책했다. 편안했다.", null);
        var revised = new DiaryOutput("발표했다. 긴장했다. 마쳤다.\n\n산책했다. 편안했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class)))
                .thenReturn(Optional.of(compressed)).thenReturn(Optional.of(revised));

        assertThat(writer.write(state)).contains(revised);
        verify(client).withReadTimeout(GeminiDiaryWriter.REVISION_TIMEOUT);
        var requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client, times(2)).generate(requests.capture(), eq(DiaryOutput.class));
        assertThat(requests.getAllValues().get(1).user()).contains(state.messages().getFirst().content(),
                compressed.diary(), "새 사실·감정이나 반복 문장으로 채우지", "정확성을 우선");
    }

    @Test
    void failedRevisionKeepsOriginalDraft() {
        var diary = new DiaryOutput("오늘 산책했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class)))
                .thenReturn(Optional.of(diary)).thenReturn(Optional.empty());

        assertThat(writer.write(state)).contains(diary);
        verify(client, times(2)).generate(any(), eq(DiaryOutput.class));
    }

    @Test
    void sparseConversationIsNotRewrittenForeverToFillSentenceCount() {
        var diary = new DiaryOutput("오늘 산책했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(diary);
        verify(client, times(2)).generate(any(), eq(DiaryOutput.class));
    }

    @Test
    void initialFailureKeepsExistingEngineFallbackWithoutAnotherGeneration() {
        when(client.generate(any(), eq(DiaryOutput.class))).thenReturn(Optional.empty());

        assertThat(writer.write(state)).isEmpty();
        verify(client).generate(any(), eq(DiaryOutput.class));
    }
}
