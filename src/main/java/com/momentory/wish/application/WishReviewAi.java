package com.momentory.wish.application;

import java.util.Optional;

/** 소원 심사를 맡는 AI — 실패하면 비어 있고, 그때 소원은 띄우지 않는다. */
public interface WishReviewAi {

    enum Decision {
        /** 띄워도 된다. */
        PASS,
        /** 쓴 사람이 위험해 보인다. */
        CRISIS,
        /** 남을 해치거나 잘못되길 바라는 말, 혐오. */
        HARM,
        /** 광고 · 홍보 · 성적인 말. */
        NOT_ALLOWED,
        /** 소원으로 읽히지 않는다. */
        NOT_A_WISH
    }

    /** 심사 결과 — {@code content} 는 이름 · 연락처만 고친 글이고 {@code light} 는 쪽지에 돌려도 되는 가벼운 글인가다. */
    record Verdict(Decision decision, String content, boolean light) {
    }

    Optional<Verdict> review(String content);
}
