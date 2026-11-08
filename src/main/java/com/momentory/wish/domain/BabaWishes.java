package com.momentory.wish.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 바바의 소원 — 사람 소원이 적은 날 연못을 채운다. 화면에 「바바의 소원」이라고 적어 사람 글인 척하지 않는다.
 *
 * <p>id 는 음수이고 서버에 저장하지 않는다. 같이 빌기 · 신고 대상이 아니다. 날짜마다 시작 자리를 옮겨
 * 며칠 연달아 같은 문장만 보이지 않게 한다.
 */
public final class BabaWishes {

    public record BabaWish(long id, String content) {
    }

    private static final List<String> LINES = List.of(
            "오늘은 귤이 달았으면 좋겠어요",
            "따뜻한 물에 오래 있고 싶어요",
            "오늘 밤에는 다들 푹 잤으면 좋겠어요",
            "풀밭에서 낮잠을 자고 싶어요",
            "좋아하는 모자를 오래 쓰고 싶어요",
            "내일도 여기서 같이 쉬면 좋겠어요",
            "연못에 비가 조금만 왔으면 좋겠어요",
            "여기 온 사람들 마음이 조금 가벼워지면 좋겠어요");

    private BabaWishes() {
    }

    public static List<BabaWish> forDay(LocalDate day, int count) {
        int start = Math.floorMod(day.toEpochDay(), LINES.size());
        return IntStream.range(0, Math.min(count, LINES.size()))
                .map(i -> (start + i) % LINES.size())
                .mapToObj(i -> new BabaWish(-(i + 1L), LINES.get(i)))
                .toList();
    }
}
