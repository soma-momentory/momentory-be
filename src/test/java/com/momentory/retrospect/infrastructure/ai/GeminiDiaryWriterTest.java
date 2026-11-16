package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

    /** 문단마다 문장 목록을 넘긴다. */
    @SafeVarargs
    private static DiaryDraft draft(List<String>... paragraphs) {
        return new DiaryDraft(java.util.Arrays.stream(paragraphs).map(DiaryDraft.Paragraph::new).toList());
    }

    @Test
    void sentencesOfOneEventSitOnConsecutiveLinesAndOnlyEventsAreSeparatedByABlankLine() {
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(
                draft(List.of("발표했다.", "긴장했다.", "마쳤다."), List.of("산책했다.", "편안했다."))));

        assertThat(writer.write(state)).contains(
                new DiaryOutput("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안했다.", null));
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void oneEventIsWrittenWithoutAnyBlankLine() {
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(draft(List.of(
                "시험을 쳤다.", "망친 것 같았다.", "분했다.", "너무 화가 났다.", "그 마음이 쉽게 가라앉지 않았다."))));

        assertThat(writer.write(state).orElseThrow().diary())
                .isEqualTo("시험을 쳤다.\n망친 것 같았다.\n분했다.\n너무 화가 났다.\n그 마음이 쉽게 가라앉지 않았다.")
                .doesNotContain("\n\n");
    }

    @Test
    void lineBreaksInsideASentenceAndEmptyEntriesDoNotMakeExtraLines() {
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(new DiaryDraft(List.of(
                new DiaryDraft.Paragraph(java.util.Arrays.asList("발표했다.\n\n", " ", null, "긴장했다.", "마쳤다.")),
                new DiaryDraft.Paragraph(List.of()),
                new DiaryDraft.Paragraph(List.of("산책했다.", "편안\n했다."))))));

        assertThat(writer.write(state).orElseThrow().diary())
                .isEqualTo("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안 했다.");
    }

    @Test
    void compressedDraftIsRevisedOnceUsingOriginalConversationAndWithoutInventingFacts() {
        state.addUserMessage("발표 때 긴장했지만 마쳤어요. 퇴근 후 산책하니 편안했어요.");
        var compressed = draft(List.of("발표하고 긴장했다.", "마쳤다."), List.of("산책했다.", "편안했다."));
        var revised = draft(List.of("발표했다.", "긴장했다.", "마쳤다."), List.of("산책했다.", "편안했다."));
        when(client.generate(any(), eq(DiaryDraft.class)))
                .thenReturn(Optional.of(compressed)).thenReturn(Optional.of(revised));

        assertThat(writer.write(state)).contains(new DiaryOutput(revised.text(), null));
        verify(client).withReadTimeout(GeminiDiaryWriter.REVISION_TIMEOUT);
        var requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client, times(2)).generate(requests.capture(), eq(DiaryDraft.class));
        assertThat(requests.getAllValues().get(1).user()).contains(state.messages().getFirst().content(),
                compressed.text(), "초안에 빠진 것을 찾아 담고", "5~8문장", "지어내지 말고",
                "문단은 사건 단위로만 나누고")
                .doesNotContain("짧은 일기를 유지");
    }

    @Test
    void longDraftIsKeptWithoutAnotherCall() {
        var diary = draft(List.of("발표했다.", "긴장했다.", "마쳤다.", "뿌듯했다."),
                List.of("산책했다.", "친구를 만났다.", "편안했다."));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(new DiaryOutput(diary.text(), null));
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void revisionThatIsNotLongerKeepsOriginalDraft() {
        var diary = draft(List.of("방탈출이 재미있었다.", "친구들을 만나 좋았다."), List.of("공부 생각에 우울했다."));
        var shorter = draft(List.of("방탈출이 재미있었지만 공부 생각에 우울했다."));
        when(client.generate(any(), eq(DiaryDraft.class)))
                .thenReturn(Optional.of(diary)).thenReturn(Optional.of(shorter));

        assertThat(writer.write(state)).contains(new DiaryOutput(diary.text(), null));
    }

    @Test
    void failedRevisionKeepsOriginalDraft() {
        var diary = draft(List.of("오늘 산책했다."));
        when(client.generate(any(), eq(DiaryDraft.class)))
                .thenReturn(Optional.of(diary)).thenReturn(Optional.empty());

        assertThat(writer.write(state)).contains(new DiaryOutput("오늘 산책했다.", null));
        verify(client, times(2)).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void sparseConversationIsNotRewrittenForeverToFillSentenceCount() {
        var diary = draft(List.of("오늘 산책했다."));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(diary));

        assertThat(writer.write(state)).contains(new DiaryOutput("오늘 산책했다.", null));
        verify(client, times(2)).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void initialFailureKeepsExistingEngineFallbackWithoutAnotherGeneration() {
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.empty());

        assertThat(writer.write(state)).isEmpty();
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void anEmptyDraftFallsBackLikeAFailure() {
        when(client.generate(any(), eq(DiaryDraft.class)))
                .thenReturn(Optional.of(new DiaryDraft(List.of(new DiaryDraft.Paragraph(List.of(" "))))));

        assertThat(writer.write(state)).isEmpty();
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void aSentenceThatStillCarriesTheWordTheUserTookBackIsLeftOut() {
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(new DiaryDraft(
                List.of(new DiaryDraft.Correction("변함", "분함")),
                List.of(new DiaryDraft.Paragraph(List.of("시험을 쳤다.", "망친 것 같았다.",
                        "변함이라고 잘못 말했다가 분함이라고 고쳤다.", "분했다.", "너무 화가 났다.",
                        "그 마음이 가라앉지 않았다."))))));

        assertThat(writer.write(state).orElseThrow().diary())
                .isEqualTo("시험을 쳤다.\n망친 것 같았다.\n분했다.\n너무 화가 났다.\n그 마음이 가라앉지 않았다.");
    }

    @Test
    void aCorrectionThatOnlyExtendsTheWordDoesNotRemoveSentences() {
        // "화" → "화남" 처럼 고친 말이 앞의 말을 품고 있으면 가려낼 수 없다 — 문장을 지우지 않는다.
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(new DiaryDraft(
                List.of(new DiaryDraft.Correction("화", "화남"), new DiaryDraft.Correction(" ", "분함")),
                List.of(new DiaryDraft.Paragraph(List.of("시험을 쳤다.", "화가 났다.", "분했다.", "속상했다.",
                        "집에 왔다."))))));

        assertThat(writer.write(state).orElseThrow().diary().lines()).hasSize(5);
    }

    @Test
    void schemaAsksForCorrectionsBeforeParagraphs() {
        assertThat(GeminiResponseSchemas.forType(DiaryDraft.class).get("propertyOrdering"))
                .isEqualTo(List.of("corrections", "paragraphs"));
    }
}
