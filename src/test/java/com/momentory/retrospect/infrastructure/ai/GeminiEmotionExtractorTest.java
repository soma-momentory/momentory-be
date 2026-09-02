package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEmotion;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEvent;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiKeyword;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiExtraction;

/**
 * 감정 추출 어댑터 — 모델 출력을 그대로 믿지 않고 정리하는 부분을 검증한다 (모델 비교 계획 §3.1).
 */
class GeminiEmotionExtractorTest {

    private final GeminiApiClient llm = mock(GeminiApiClient.class);
    private final GeminiEmotionExtractor extractor =
            new GeminiEmotionExtractor(llm, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));

    private static RetrospectState state() {
        RetrospectState state = new RetrospectState("sess-1");
        state.addUserMessage("오늘 발표에서 말이 막혔어요");
        return state;
    }

    private void respond(GeminiExtraction extraction) {
        when(llm.generate(any(), eq(GeminiExtraction.class))).thenReturn(Optional.of(extraction));
    }

    @Test
    @DisplayName("사건·감정을 도메인 타입으로 매핑한다")
    void mapsToDomainTypes() {
        respond(new GeminiExtraction(
                List.of(new GeminiEvent(1, "발표", "발표에서 말이 막힘", List.of(1))),
                List.of(new GeminiEmotion(1, "너무 불안했어요", "anxious", 3, "before",
                        "준비가 부족했어요", List.of(1))),
                List.of(new GeminiKeyword("발표", 1))));

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).summary()).isEqualTo("발표에서 말이 막힘");
        assertThat(result.emotions()).hasSize(1);
        assertThat(result.emotions().get(0).normalized()).isEqualTo(Emotion.ANXIOUS);
        assertThat(result.emotions().get(0).phase()).isEqualTo(EmotionPhase.BEFORE);
        assertThat(result.emotions().get(0).intensity()).isEqualTo(3);
    }

    @Test
    @DisplayName("실제 사건을 가리키지 않는 eventId 는 떨군다 — 유령 참조는 사건 귀속 채점을 오염시킨다")
    void dropsDanglingEventId() {
        respond(new GeminiExtraction(
                List.of(new GeminiEvent(1, "발표", "발표에서 말이 막힘", List.of(1))),
                List.of(new GeminiEmotion(7, "불안했어요", "anxious", 2, "now", "불안해요",
                        List.of(1))),
                List.of()));

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.emotions().get(0).eventId()).isNull();
        assertThat(result.emotions().get(0).normalized()).isEqualTo(Emotion.ANXIOUS);
    }

    @Test
    @DisplayName("사건은 상한 2개까지만 남기고, 요약 없는 사건은 버린다")
    void capsAndFiltersEvents() {
        respond(new GeminiExtraction(
                List.of(new GeminiEvent(1, "발표", "발표에서 말이 막힘", List.of(1)),
                        new GeminiEvent(2, "빈 사건", "  ", List.of(2)),
                        new GeminiEvent(3, "다툼", "친구와 다툼", List.of(3)),
                        new GeminiEvent(4, "산책", "저녁 산책", List.of(4))),
                List.of(),
                List.of()));

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.events()).hasSize(RetrospectState.MAX_EVENTS);
        assertThat(result.events()).extracting("summary")
                .containsExactly("발표에서 말이 막힘", "친구와 다툼");
    }

    @Test
    @DisplayName("모르는 감정 키·시점은 null 로 둔다 — 고정 10종 밖은 받지 않는다")
    void unknownKeysBecomeNull() {
        respond(new GeminiExtraction(
                List.of(),
                List.of(new GeminiEmotion(null, "설렜어요", "excited", 2, "someday", "설렜어요",
                        List.of(1))),
                List.of()));

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.emotions().get(0).normalized()).isNull();
        assertThat(result.emotions().get(0).phase()).isNull();
        assertThat(result.emotions().get(0).raw()).isEqualTo("설렜어요");
    }

    @Test
    @DisplayName("호출이 실패하면 빈 결과를 준다 — 대화를 끊지 않는다")
    void emptyOnFailure() {
        when(llm.generate(any(), eq(GeminiExtraction.class))).thenReturn(Optional.empty());

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.events()).isEmpty();
        assertThat(result.emotions()).isEmpty();
    }

    @Test
    @DisplayName("키워드도 같은 콜에서 받는다 — 상한 2개, 빈 라벨은 버린다")
    void mapsKeywords() {
        respond(new GeminiExtraction(
                List.of(new GeminiEvent(1, "발표", "발표에서 말이 막힘", List.of(1))),
                List.of(),
                List.of(new GeminiKeyword("발표", 1), new GeminiKeyword("  ", null),
                        new GeminiKeyword("준비", null), new GeminiKeyword("버려질 것", 1))));

        EmotionExtraction result = extractor.extract(state());

        assertThat(result.keywords()).hasSize(RetrospectState.MAX_KEYWORDS);
        assertThat(result.keywords()).extracting("label").containsExactly("발표", "준비");
        assertThat(result.keywords().get(0).eventId()).isEqualTo(1);
        assertThat(result.keywords().get(1).eventId()).isNull();
    }

    @Test
    @DisplayName("키워드의 유령 eventId 도 떨군다")
    void dropsDanglingKeywordEventId() {
        respond(new GeminiExtraction(
                List.of(new GeminiEvent(1, "발표", "발표에서 말이 막힘", List.of(1))),
                List.of(),
                List.of(new GeminiKeyword("발표", 9))));

        assertThat(extractor.extract(state()).keywords().get(0).eventId()).isNull();
    }
}
