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
    void excludedEventInFirstPositionIsFilteredByIdentityBeforeAnyParagraphLimit() {
        state.events(List.of(new com.momentory.retrospect.domain.ExtractedEvent(1, "발표", "발표했다", List.of(1)),
                new com.momentory.retrospect.domain.ExtractedEvent(2, "산책", "산책했다", List.of(1))));
        var result = new DiaryDraft(List.of(
                new DiaryDraft.Paragraph(3, "간식", List.of("간식을 먹었다.")),
                new DiaryDraft.Paragraph(1, "발표", List.of("발표했다.", "긴장했다.", "마쳤다.")),
                new DiaryDraft.Paragraph(2, "산책", List.of("산책했다.", "편안했다."))));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(result));
        assertThat(writer.write(state).orElseThrow().diary())
                .isEqualTo("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안했다.")
                .doesNotContain("간식");
    }

    @Test
    void unknownOrMissingSelectedEventsFallBackRatherThanSavingAnUnselectedEvent() {
        state.events(List.of(new com.momentory.retrospect.domain.ExtractedEvent(1, "발표", "발표했다", List.of(1)),
                new com.momentory.retrospect.domain.ExtractedEvent(2, "산책", "산책했다", List.of(1))));
        var result = new DiaryDraft(List.of(new DiaryDraft.Paragraph(3, "간식", List.of("잘못 연결된 다른 사건")),
                new DiaryDraft.Paragraph(2, "산책", List.of("산책했다."))));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(result));
        assertThat(writer.write(state)).isEmpty();
        verify(client, times(2)).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void aMissingEventIdCannotDuplicateOneParagraphAcrossEventsWithTheSameName() {
        state.events(List.of(new com.momentory.retrospect.domain.ExtractedEvent(1, "운동", "오전에 운동했다", List.of(1)),
                new com.momentory.retrospect.domain.ExtractedEvent(2, "운동", "저녁에 운동했다", List.of(2))));
        var result = new DiaryDraft(List.of(new DiaryDraft.Paragraph("운동", List.of("운동했다."))));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(result));
        assertThat(writer.write(state)).isEmpty();
    }

    @Test
    void aDifferentEventLabelDoesNotDiscardTheDiaryWhenItsIdMatches() {
        state.events(List.of(new com.momentory.retrospect.domain.ExtractedEvent(4, "휴식", "집에서 쉬었다", List.of(1, 2))));
        var result = new DiaryDraft(List.of(new DiaryDraft.Paragraph(4, "집에서 보낸 휴식 시간", List.of(
                "집에 돌아왔다.", "씻었다.", "편하게 누웠다.", "쉬는 시간이 좋았다.", "마음이 편안했다."))));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(result));
        assertThat(writer.write(state).orElseThrow().diary()).isEqualTo(result.text());
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void aRenumberedOrMissingEventIsRepairedOnceBeforeFallingBackToASummary() {
        state.events(List.of(new com.momentory.retrospect.domain.ExtractedEvent(4, "휴식", "집에서 쉬었다", List.of(1))));
        state.addUserMessage("집에서 쉬며 편안했다.");
        var invalid = new DiaryDraft(List.of(new DiaryDraft.Paragraph(1, "휴식", List.of("누웠다."))));
        var repaired = new DiaryDraft(List.of(new DiaryDraft.Paragraph(4, "휴식", List.of(
                "집에 있었다.", "누워 있었다.", "쉬고 싶었다.", "편안했다.", "푹 쉬었다."))));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(invalid)).thenReturn(Optional.of(repaired));
        assertThat(writer.write(state).orElseThrow().diary()).isEqualTo(repaired.text());
        var requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client, times(2)).generate(requests.capture(), eq(DiaryDraft.class));
        assertThat(requests.getAllValues().get(1).user()).contains("[사건 연결 다시 쓰기]", "[사건 4]", "새로 번호를 매기지 마세요");
        verify(client).withReadTimeout(GeminiDiaryWriter.REVISION_TIMEOUT);
    }

    @Test
    void thirdEventParagraphIsNeverIncludedInTheDiary() {
        var result = draft(List.of("발표했다.", "긴장했다.", "마쳤다."),
                List.of("산책했다.", "편안했다."), List.of("선정하지 않은 간식을 먹었다."));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(result));
        assertThat(writer.write(state).orElseThrow().diary())
                .isEqualTo("발표했다.\n긴장했다.\n마쳤다.\n\n산책했다.\n편안했다.");
        verify(client).generate(any(), eq(DiaryDraft.class));
    }

    @Test
    void extraEventCannotFillTheMinimumAndIsAlsoRemovedFromTheRevision() {
        var first = draft(List.of("발표했다.", "긴장했다."), List.of("산책했다.", "편안했다."),
                List.of("다른 사건 하나.", "다른 사건 둘."));
        var revised = draft(List.of("발표했다.", "긴장했다.", "마쳤다."), List.of("산책했다.", "편안했다."),
                List.of("다른 사건 셋."));
        when(client.generate(any(), eq(DiaryDraft.class))).thenReturn(Optional.of(first)).thenReturn(Optional.of(revised));
        assertThat(writer.write(state).orElseThrow().diary()).doesNotContain("다른 사건");
        verify(client, times(2)).generate(any(), eq(DiaryDraft.class));
        java.util.Map<?, ?> properties = (java.util.Map<?, ?>) GeminiResponseSchemas.forType(DiaryDraft.class).get("properties");
        java.util.Map<?, ?> paragraphs = (java.util.Map<?, ?>) properties.get("paragraphs");
        assertThat(paragraphs.get("maxItems")).isEqualTo(2);
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
