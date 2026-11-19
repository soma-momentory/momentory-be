package com.momentory.letter.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.Candidate;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.Content;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.InlineData;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.Part;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.Response;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Cast;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Panel;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Plan;

class GeminiPictureDrawerTest {

    private static final Plan PLAN = new Plan(List.of(new Cast("stern teacher", "badger")), List.of(
            new Panel("a school hallway", "Baba holds a notebook", "calm", "none"),
            new Panel("an office doorway", "a badger teacher waves Baba away", "shocked", "the badger"),
            new Panel("the hallway", "Baba stands frozen", "stunned", ""),
            new Panel("a bedroom", "Baba rests with a warm mug", "still annoyed", null)));

    @Test
    void picksFirstImagePartAndSkipsTextParts() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        Response response = new Response(List.of(new Candidate(new Content(List.of(
                new Part(null),
                new Part(new InlineData("text/plain", "aGk=")),
                new Part(new InlineData("image/png", Base64.getEncoder().encodeToString(png))))))), null, null);

        GeminiPictureDrawer.Drawn drawn = GeminiPictureDrawer.imageOf(response).orElseThrow();

        assertThat(drawn.mimeType()).isEqualTo("image/png");
        assertThat(drawn.bytes()).containsExactly(png);
    }

    @Test
    void noImageMeansNothing() {
        assertThat(GeminiPictureDrawer.imageOf(null)).isEmpty();
        assertThat(GeminiPictureDrawer.imageOf(new Response(null, null, null))).isEmpty();
        assertThat(GeminiPictureDrawer.imageOf(new Response(List.of(new Candidate(null)), null, null))).isEmpty();
    }

    @Test
    void promptCarriesFourPanelsCastAndNoTextRule() {
        String prompt = GeminiPictureDrawer.prompt(PLAN);

        assertThat(prompt).contains("Panel 1: setting: a school hallway", "Panel 4: setting: a bedroom",
                "stern teacher = a badger", "no writing of any kind", "4-panel comic");
        assertThat(prompt).contains("Others: none."); // 빈 칸 · null 은 「없음」으로 적는다
        assertThat(prompt).doesNotContain("Panel 5");
    }

    @Test
    void scenePlanMustHaveFourFilledPanels() {
        assertThat(GeminiScenePlanner.complete(PLAN)).isTrue();
        assertThat(GeminiScenePlanner.complete(new Plan(List.of(), PLAN.panels().subList(0, 3)))).isFalse();
        assertThat(GeminiScenePlanner.complete(new Plan(List.of(), List.of(PLAN.panels().get(0), PLAN.panels().get(1),
                PLAN.panels().get(2), new Panel("a room", " ", "calm", "none"))))).isFalse();
    }
}
