package com.momentory.wish.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WishReviewTokenTest {

    private final WishReviewToken token = new WishReviewToken("secret");
    private final Instant now = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void acceptsSameUserAndTextWithinValidPeriod() {
        String issued = token.issue(1L, "소원", true, now);

        assertThat(token.verify(1L, "소원", issued, now.plus(WishReviewToken.VALID_FOR))).contains(true);
        assertThat(token.verify(1L, "소원", token.issue(1L, "소원", false, now), now)).contains(false);
    }

    @Test
    void rejectsOtherTextUserExpiredAndTampered() {
        String issued = token.issue(1L, "소원", false, now);

        assertThat(token.verify(1L, "다른 글", issued, now)).isEmpty();
        assertThat(token.verify(2L, "소원", issued, now)).isEmpty();
        assertThat(token.verify(1L, "소원", issued, now.plus(WishReviewToken.VALID_FOR).plusSeconds(1))).isEmpty();
        assertThat(token.verify(1L, "소원", issued.replace(".0.", ".1."), now)).isEmpty();
        assertThat(token.verify(1L, "소원", "엉터리", now)).isEmpty();
        assertThat(token.verify(1L, "소원", null, now)).isEmpty();
    }
}
