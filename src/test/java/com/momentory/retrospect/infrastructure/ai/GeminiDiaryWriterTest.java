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
        var diary = new DiaryOutput("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(diary);
        verify(client).generate(any(), eq(DiaryOutput.class));
    }

    @Test
    void compressedDraftIsRevisedOnceUsingOriginalConversationAndWithoutInventingFacts() {
        state.addUserMessage("발표 때 긴장했지만 마쳤어요. 퇴근 후 산책하니 편안했어요.");
        var compressed = new DiaryOutput("발표하고 긴장했다. 마쳤다. 산책했다. 편안했다.", null);
        var revised = new DiaryOutput("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class)))
                .thenReturn(Optional.of(compressed)).thenReturn(Optional.of(revised));

        assertThat(writer.write(state)).contains(revised);
        verify(client).withReadTimeout(GeminiDiaryWriter.REVISION_TIMEOUT);
        var requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client, times(2)).generate(requests.capture(), eq(DiaryOutput.class));
        assertThat(requests.getAllValues().get(1).user()).contains(state.messages().getFirst().content(),
                compressed.diary(), "초안에 빠진 것을 찾아 담고", "5~8문장", "지어내지 말고",
                "한 문장마다 줄바꿈", "맥락이 바뀌는 곳에만 빈 줄 하나")
                .doesNotContain("짧은 일기를 유지");
    }

    @Test
    void longDraftIsKeptWithoutAnotherCall() {
        var diary = new DiaryOutput("발표했다.\n긴장했다.\n마쳤다.\n뿌듯했다.\n\n산책했다.\n친구를 만났다.\n편안했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(diary);
        verify(client).generate(any(), eq(DiaryOutput.class));
    }

    @Test
    void revisionThatIsNotLongerKeepsOriginalDraft() {
        var diary = new DiaryOutput("방탈출이 재미있었다.\n친구들을 만나 좋았다.\n\n공부 생각에 우울했다.", null);
        var shorter = new DiaryOutput("방탈출이 재미있었지만 공부 생각에 우울했다.", null);
        when(client.generate(any(), eq(DiaryOutput.class)))
                .thenReturn(Optional.of(diary)).thenReturn(Optional.of(shorter));

        assertThat(writer.write(state)).contains(diary);
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
