package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmotionTest {

    @Test
    @DisplayName("대표 감정 12종 — 키와 선언 순서")
    void declarationOrder() {
        assertThat(Emotion.keys()).containsExactly(
                "joy", "calm", "proud", "affection", "sad", "anxious", "angry",
                "disappointed", "ashamed", "confused", "sorry", "exhausted");
    }

    @Test
    @DisplayName("라벨 — 12종 이름 그대로")
    void labels() {
        assertThat(Emotion.values()).extracting(Emotion::label).containsExactly(
                "기쁨", "평온", "뿌듯함", "애정", "슬픔", "불안", "분노",
                "실망", "부끄러움", "혼란", "미안함", "지침");
    }

    @Test
    @DisplayName("열거 상수 이름은 키의 대문자다 — 저장된 이름과 키를 한 규칙으로 읽는다")
    void constantNameIsUppercaseKey() {
        for (Emotion e : Emotion.values()) {
            assertThat(e.name()).isEqualTo(e.key().toUpperCase());
        }
    }

    @Test
    @DisplayName("긍정 감정 4종이 강점 신호로 쓰인다")
    void positiveEmotions() {
        assertThat(Emotion.POSITIVE).containsExactlyInAnyOrder(
                Emotion.JOY, Emotion.CALM, Emotion.PROUD, Emotion.AFFECTION);
        assertThat(Emotion.AFFECTION.isPositive()).isTrue();
        assertThat(Emotion.ANXIOUS.isPositive()).isFalse();
        assertThat(Emotion.SORRY.isPositive()).isFalse();
    }

    @Test
    @DisplayName("알 수 없는 키는 라벨 변환에서 그대로 통과한다 (원본 label() 동작)")
    void unknownKeyPassesThrough() {
        assertThat(Emotion.labelOf("nonsense")).isEqualTo("nonsense");
        assertThat(Emotion.labelOf(null)).isNull();
    }

    @Test
    void validation() {
        assertThat(Emotion.isValid("anxious")).isTrue();
        assertThat(Emotion.isValid("sorry")).isTrue();
        assertThat(Emotion.isValid("nonsense")).isFalse();
        assertThat(Emotion.isValid(null)).isFalse();
        assertThat(Emotion.fromKey("calm")).contains(Emotion.CALM);
        assertThat(Emotion.fromKey("nope")).isEmpty();
        assertThat(Emotion.fromKey(null)).isEmpty();
    }

    @Test
    @DisplayName("옛 10종 키는 새 감정으로 옮겨 받는다 — 답답함은 분노, 무기력·피곤함은 지침")
    void legacyKeysMapToNewEmotions() {
        assertThat(Emotion.fromKey("happy")).contains(Emotion.JOY);
        assertThat(Emotion.fromKey("depressed")).contains(Emotion.SAD);
        assertThat(Emotion.fromKey("frustrated")).contains(Emotion.ANGRY);
        assertThat(Emotion.fromKey("stuck")).contains(Emotion.CONFUSED);
        assertThat(Emotion.fromKey("lethargic")).contains(Emotion.EXHAUSTED);
        assertThat(Emotion.fromKey("tired")).contains(Emotion.EXHAUSTED);
        // 키가 그대로인 넷
        assertThat(Emotion.fromKey("anxious")).contains(Emotion.ANXIOUS);
        assertThat(Emotion.fromKey("angry")).contains(Emotion.ANGRY);
        assertThat(Emotion.fromKey("proud")).contains(Emotion.PROUD);
        assertThat(Emotion.fromKey("calm")).contains(Emotion.CALM);
    }

    @Test
    @DisplayName("옛 키는 읽히지만 유효한 키로 치지 않고, 라벨은 새 이름으로 나온다")
    void legacyKeysAreReadableButNotCanonical() {
        assertThat(Emotion.isValid("happy")).isFalse();
        assertThat(Emotion.keys()).doesNotContain("happy", "depressed", "frustrated", "stuck",
                "lethargic", "tired");
        assertThat(Emotion.labelOf("frustrated")).isEqualTo("분노");
        assertThat(Emotion.labelOf("tired")).isEqualTo("지침");
    }

    @Test
    @DisplayName("저장된 값 — 상수 이름·키·옛 이름을 모두 읽고, 모르는 값은 비운다")
    void fromStoredReadsNamesKeysAndLegacy() {
        assertThat(Emotion.fromStored("JOY")).contains(Emotion.JOY);
        assertThat(Emotion.fromStored("joy")).contains(Emotion.JOY);
        assertThat(Emotion.fromStored("HAPPY")).contains(Emotion.JOY);
        assertThat(Emotion.fromStored("FRUSTRATED")).contains(Emotion.ANGRY);
        assertThat(Emotion.fromStored("LETHARGIC")).contains(Emotion.EXHAUSTED);
        assertThat(Emotion.fromStored(" STUCK ")).contains(Emotion.CONFUSED);
        assertThat(Emotion.fromStored("ANGRY_FRUSTRATED")).isEmpty();
        assertThat(Emotion.fromStored(null)).isEmpty();
    }

    @Test
    @DisplayName("라벨로 찾기 — 새 라벨과, 진행 중이던 세션의 옛 라벨을 모두 받는다")
    void fromLabelAcceptsNewAndLegacyLabels() {
        assertThat(Emotion.fromLabel("미안함")).contains(Emotion.SORRY);
        assertThat(Emotion.fromLabel(" 부끄러움 ")).contains(Emotion.ASHAMED);
        assertThat(Emotion.fromLabel("뿌듯함")).contains(Emotion.PROUD);
        assertThat(Emotion.fromLabel("답답함")).contains(Emotion.ANGRY);
        assertThat(Emotion.fromLabel("화남")).contains(Emotion.ANGRY);
        assertThat(Emotion.fromLabel("우울함")).contains(Emotion.SAD);
        assertThat(Emotion.fromLabel("막막함")).contains(Emotion.CONFUSED);
        assertThat(Emotion.fromLabel("무기력")).contains(Emotion.EXHAUSTED);
        assertThat(Emotion.fromLabel("모르겠어요")).isEmpty();
        assertThat(Emotion.fromLabel(null)).isEmpty();
    }

    @Test
    @DisplayName("어근 — '-하다'를 붙였을 때 문장이 되는 말이다")
    void stemsTakeHada() {
        assertThat(Emotion.JOY.stem() + "했고").isEqualTo("행복했고");
        assertThat(Emotion.SAD.stem() + "한 마음").isEqualTo("속상한 마음");
        assertThat(Emotion.ANGRY.stem() + "하다고").isEqualTo("분하다고");
        assertThat(Emotion.EXHAUSTED.stem() + "했고").isEqualTo("피곤했고");
        assertThat(Emotion.SORRY.stem() + "한 마음").isEqualTo("미안한 마음");
        for (Emotion e : Emotion.values()) {
            // 명사형 라벨(기쁨·슬픔·지침·부끄러움·미안함·뿌듯함)을 그대로 어근으로 쓰지 않는다.
            assertThat(e.stem()).doesNotEndWith("음").doesNotEndWith("함").doesNotEndWith("침")
                    .doesNotEndWith("쁨").doesNotEndWith("픔");
        }
    }
}
