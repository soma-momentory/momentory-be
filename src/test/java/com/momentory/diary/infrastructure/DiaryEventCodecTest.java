package com.momentory.diary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.diary.domain.DiaryEvent;
import com.momentory.retrospect.domain.Emotion;

import tools.jackson.databind.json.JsonMapper;

class DiaryEventCodecTest {

    private final DiaryEventCodec codec = new DiaryEventCodec(JsonMapper.builder().build());

    @Test
    @DisplayName("사건과 감정을 접었다 펴도 같다 — 새 이름으로 저장한다")
    void roundTrips() {
        String json = codec.serialize(List.of(
                new DiaryEvent("발표", "발표를 마침", List.of(Emotion.PROUD, Emotion.SORRY))));

        assertThat(json).contains("PROUD", "SORRY");
        assertThat(codec.deserialize(json)).singleElement().satisfies(event -> {
            assertThat(event.label()).isEqualTo("발표");
            assertThat(event.emotions()).containsExactly(Emotion.PROUD, Emotion.SORRY);
        });
    }

    @Test
    @DisplayName("옛 10종 감정 이름으로 저장된 사건을 새 12종으로 옮겨 읽는다")
    void readsEventsSavedWithLegacyEmotionNames() {
        String legacy = """
                {"events":[{"label":"야근","summary":"늦게까지 일함",
                  "emotions":["TIRED","FRUSTRATED","HAPPY","DEPRESSED","STUCK","CALM"]}]}
                """;

        assertThat(codec.deserialize(legacy)).singleElement().satisfies(event ->
                assertThat(event.emotions()).containsExactly(Emotion.EXHAUSTED, Emotion.ANGRY,
                        Emotion.JOY, Emotion.SAD, Emotion.CONFUSED, Emotion.CALM));
    }

    @Test
    void nullJsonIsEmpty() {
        assertThat(codec.deserialize(null)).isEmpty();
    }
}
