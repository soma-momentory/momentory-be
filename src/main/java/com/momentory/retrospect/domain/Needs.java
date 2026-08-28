package com.momentory.retrospect.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * '바람(욕구)' 고정 목록 — 단어 + 뜻 (채팅흐름_v2 Phase 3 지정 욕구 풀, 결정 ③).
 *
 * <p>감정 탐색 2턴에서 대화 맥락에 맞는 3~4개를 골라 제시한다. 정규화(사용자가 고른 바람 → 이 단어)
 * 와 프롬프트에 실을 목록의 <b>단일 정본</b>이다. 문구(뜻)는 제품 초안이며 이후 다듬을 수 있다.
 */
public final class Needs {

    /** 선언 순서 = v2 지정 욕구 풀 순서. */
    public static final List<Need> ALL = List.of(
            new Need("의지", "스스로 정한 방향으로 밀고 나가고 싶은 마음"),
            new Need("노력", "들인 수고가 헛되지 않길 바라는 마음"),
            new Need("열정", "마음이 뜨겁게 몰입하고 싶은 마음"),
            new Need("소속감", "어딘가에 속해 함께이고 싶은 마음"),
            new Need("효율성", "낭비 없이 매끄럽게 해내고 싶은 마음"),
            new Need("부", "넉넉함으로 마음이 놓이길 바라는 마음"),
            new Need("재미", "즐겁고 신나는 순간을 누리고 싶은 마음"),
            new Need("자유", "얽매이지 않고 내 뜻대로이고 싶은 마음"),
            new Need("지혜", "깊이 이해하고 슬기롭게 판단하고 싶은 마음"),
            new Need("용기", "두려워도 한 걸음 내딛고 싶은 마음"),
            new Need("도전", "새로운 것에 부딪혀 보고 싶은 마음"),
            new Need("영향력", "내 뜻이 무언가를 움직이길 바라는 마음"),
            new Need("선택", "내 삶을 내가 고르고 싶은 마음"),
            new Need("성취감", "이뤄낸 결과로 뿌듯하고 싶은 마음"),
            new Need("꿈", "바라는 미래를 향해 나아가고 싶은 마음"),
            new Need("자신감", "나를 믿고 당당하고 싶은 마음"),
            new Need("인정", "내 노력과 존재를 알아봐 주길 바라는 마음"),
            new Need("자기계발", "어제보다 나아지고 싶은 마음"),
            new Need("성찰", "나를 돌아보고 깊어지고 싶은 마음"),
            new Need("배려", "서로를 헤아리고 챙기고 싶은 마음"),
            new Need("평안", "마음이 고요하고 편안하길 바라는 마음"),
            new Need("휴식", "지친 몸과 마음을 쉬게 하고 싶은 마음"),
            new Need("관계", "사람들과 이어져 있고 싶은 마음"),
            new Need("나눔", "가진 것을 함께 나누고 싶은 마음"),
            new Need("건강", "몸과 마음이 온전하길 바라는 마음"),
            new Need("균형", "어느 한쪽에 치우치지 않고 싶은 마음"),
            new Need("정직", "있는 그대로 솔직하고 싶은 마음"),
            new Need("책임감", "내 몫을 끝까지 지고 싶은 마음"),
            new Need("성실", "꾸준하고 미덥게 해내고 싶은 마음"),
            new Need("용서", "무거운 마음을 내려놓고 싶은 마음"),
            new Need("희망", "나아질 거라 믿고 싶은 마음"),
            new Need("정리", "흐트러진 것을 가지런히 하고 싶은 마음"),
            new Need("소통", "마음이 서로 잘 통하길 바라는 마음"),
            new Need("인내", "힘들어도 견뎌내고 싶은 마음"),
            new Need("정의", "옳고 공정하길 바라는 마음"),
            new Need("신뢰", "서로 믿고 기댈 수 있길 바라는 마음"),
            new Need("전문성", "잘 해내는 사람이고 싶은 마음"),
            new Need("진실됨", "꾸밈없이 참되고 싶은 마음"),
            new Need("소신", "내 생각을 지키고 싶은 마음"),
            new Need("존중", "내 의견과 존재가 가볍게 다뤄지지 않길 바라는 마음"),
            new Need("봉사", "누군가에게 도움이 되고 싶은 마음"),
            new Need("창의성", "나만의 방식으로 만들어내고 싶은 마음"),
            new Need("치유", "아픈 마음이 낫길 바라는 마음"),
            new Need("독립", "스스로 서고 싶은 마음"),
            new Need("아름다움", "아름다운 것을 곁에 두고 싶은 마음"),
            new Need("매력", "나답게 빛나고 싶은 마음"),
            new Need("우정", "마음 맞는 벗과 함께이고 싶은 마음"),
            new Need("사랑", "아끼고 아낌받고 싶은 마음"),
            new Need("이해", "내 마음을 알아주길 바라는 마음"),
            new Need("공감", "서로의 마음을 깊이 이해하고 나누고 싶은 마음"));

    private static final Map<String, Need> BY_WORD;

    static {
        Map<String, Need> m = new LinkedHashMap<>();
        for (Need n : ALL) {
            m.put(n.word(), n);
        }
        BY_WORD = Collections.unmodifiableMap(m);
    }

    private Needs() {
    }

    /** 단어로 고정 욕구를 찾는다 — 목록 밖(직접 적기)이면 empty. */
    public static Optional<Need> byWord(String word) {
        return Optional.ofNullable(word == null ? null : BY_WORD.get(word.strip()));
    }

    public static boolean contains(String word) {
        return word != null && BY_WORD.containsKey(word.strip());
    }
}
