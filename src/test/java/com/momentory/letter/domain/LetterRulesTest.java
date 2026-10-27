package com.momentory.letter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.common.time.DayBoundary;

class LetterRulesTest {

    @Test
    void quoteMatchesWhenOnlySpacesAndPunctuationDiffer() {
        List<String> sources = List.of("발표 전에, 손이 계속 떨렸어요…", "그래도 끝까지 했어요");

        assertThat(LetterRules.isVerbatim("발표 전에 손이 계속 떨렸어요!", sources)).isTrue();
        assertThat(LetterRules.isVerbatim("「그래도 끝까지 했어요」", sources)).isTrue();
    }

    @Test
    void fabricatedOrMergedOrEmptyQuoteFails() {
        List<String> sources = List.of("발표 전에 손이 떨렸어요", "그래도 끝까지 했어요");

        assertThat(LetterRules.isVerbatim("발표 전에 너무 무서웠어요", sources)).isFalse();
        assertThat(LetterRules.isVerbatim("떨렸어요 그래도 끝까지", sources)).isFalse();
        assertThat(LetterRules.isVerbatim("...", sources)).isFalse();
        assertThat(LetterRules.isVerbatim(null, sources)).isFalse();
    }

    @Test
    void fallbackQuoteIsFirstSentenceCutAtForty() {
        assertThat(LetterRules.fallbackQuote("오늘은 발표가 있었다. 떨렸다.")).isEqualTo("오늘은 발표가 있었다.");
        assertThat(LetterRules.fallbackQuote("가".repeat(50))).hasSize(LetterRules.QUOTE_MAX_LENGTH);
        assertThat(LetterRules.fallbackQuote("  ")).isNull();
        assertThat(LetterRules.fallbackQuote("오늘 아침에 늦잠을 잤다. 그래도 괜찮았다.")).isEqualTo("아침에 늦잠을 잤다.");
    }

    @Test
    void defaultBodyRotatesByDateAndQuietBodyNamesUser() {
        LocalDate day = LocalDate.of(2026, 10, 26);
        assertThat(List.of(LetterRules.defaultBody(day), LetterRules.defaultBody(day.plusDays(1)),
                LetterRules.defaultBody(day.plusDays(2)))).containsExactlyInAnyOrderElementsOf(LetterRules.DEFAULT_BODIES);
        assertThat(LetterRules.defaultBody(day)).isEqualTo(LetterRules.defaultBody(day.plusDays(3)));

        // 화면이 「OO님에게,」를 붙이므로 본문에는 닉네임이 없다.
        assertThat(LetterRules.quietBody()).startsWith("어제는 많이 힘든 하루였죠.");
    }

    @Test
    void tonightQuestionMustKeepFormAndNameSomething() {
        assertThat(LetterRules.isTonightForm("오늘 밤엔 콩이 소식부터 물어볼게요.")).isTrue();
        assertThat(LetterRules.isTonightForm("오늘 밤엔 엄마와의 일이 어떻게 됐는지부터 물어볼게요")).isTrue();
        assertThat(LetterRules.isTonightForm("오늘 밤엔 무슨 이야기부터 물어볼게요.")).isFalse();
        assertThat(LetterRules.isTonightForm("오늘 밤엔 숨을 고르며 이야기해 볼래요.")).isFalse();
        assertThat(LetterRules.isTonightForm("오늘 밤엔 무엇이 힘들었는지 물어볼까요?")).isFalse();
        assertThat(LetterRules.isTonightForm(LetterRules.DEFAULT_TONIGHT_QUESTION)).isTrue();
    }

    @Test
    void leadingTimeWordIsStrippedFromQuote() {
        assertThat(LetterRules.withoutLeadingTimeWord("어제 새벽 네 시까지 잠이 안 왔어요")).isEqualTo("새벽 네 시까지 잠이 안 왔어요");
        assertThat(LetterRules.withoutLeadingTimeWord("오늘 드디어 헬스장 갔어요!")).isEqualTo("드디어 헬스장 갔어요!");
        assertThat(LetterRules.withoutLeadingTimeWord("오늘은 좀 괜찮았어요")).isEqualTo("오늘은 좀 괜찮았어요");
    }

    @Test
    void greetingWithNicknameIsStrippedFromInsight() {
        assertThat(LetterRules.withoutGreeting("하늘님, 끝까지 해낸 기억이 남을지도 몰라요.", "하늘"))
                .isEqualTo("끝까지 해낸 기억이 남을지도 몰라요.");
        assertThat(LetterRules.withoutGreeting("하늘님은 많이 지치셨군요.", "하늘")).isEqualTo("하늘님은 많이 지치셨군요.");
        assertThat(LetterRules.withoutGreeting(" 관점 ", null)).isEqualTo("관점");
    }

    @Test
    void letterOpensAtFourAmNextDayAndIsNewForTwoDays() {
        Letter letter = Letter.pending(1L, 1L, LocalDate.of(2026, 10, 26));

        LocalDate before = DayBoundary.toLocalDate(Instant.parse("2026-10-26T18:59:59Z")); // 10/27 03:59:59 KST
        LocalDate after = DayBoundary.toLocalDate(Instant.parse("2026-10-26T19:00:00Z"));  // 10/27 04:00 KST
        assertThat(letter.isOpenableOn(before)).isFalse();
        assertThat(letter.isOpenableOn(after)).isTrue();
        assertThat(letter.isNewOn(after)).isTrue();
        assertThat(letter.isNewOn(after.plusDays(1))).isTrue();
        assertThat(letter.isNewOn(after.plusDays(2))).isFalse();
        assertThat(letter.isOpenableOn(after.plusDays(2))).isTrue();
    }
}
