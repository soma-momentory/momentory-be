package com.momentory.letter.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LetterPicturePropertiesTest {

    @Test
    void offByDefaultAndCheapestModels() {
        LetterPictureProperties p = new LetterPictureProperties(false, null, null, " ");

        assertThat(p.allows(1L)).isFalse();
        assertThat(p.planModel()).isEqualTo("gemini-3.8-flash");
        assertThat(p.imageModel()).isEqualTo("gemini-3.1-flash-lite-image");
    }

    @Test
    void emptyListMeansEveryoneAndFilledListOnlyThoseUsers() {
        assertThat(new LetterPictureProperties(true, List.of(), null, null).allows(7L)).isTrue();

        LetterPictureProperties team = new LetterPictureProperties(true, List.of(1L, 3L), null, null);
        assertThat(team.allows(3L)).isTrue();
        assertThat(team.allows(7L)).isFalse();
    }
}
