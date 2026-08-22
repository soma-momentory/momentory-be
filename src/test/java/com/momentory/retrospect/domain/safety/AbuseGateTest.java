package com.momentory.retrospect.domain.safety;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.safety.AbuseGate.Category;

/**
 * 어뷰징 가드 — 회고 가치가 0 인 입력만 잡고, 감정 표출형 욕(핵심 콘텐츠)은 통과시킨다.
 *
 * <p>핵심 경계: 욕이라는 단어가 아니라, 욕을 걷어내면 반영할 내용이 없는지(PROFANITY_ONLY)·
 * 상담사를 향한 공격인지(DIRECTED_ABUSE)라는 구조로 갈린다.
 */
class AbuseGateTest {

    @Test
    @DisplayName("욕만 있는 한마디는 PROFANITY_ONLY 로 잡는다 — 삽입문자·자모·반복 우회 포함")
    void detectsProfanityOnly() {
        assertThat(AbuseGate.inspect("씨발")).contains(Category.PROFANITY_ONLY);
        assertThat(AbuseGate.inspect("아 씨발")).contains(Category.PROFANITY_ONLY);
        assertThat(AbuseGate.inspect("씨발ㅋㅋ")).contains(Category.PROFANITY_ONLY);
        assertThat(AbuseGate.inspect("시.발")).contains(Category.PROFANITY_ONLY);   // 삽입문자 우회
        assertThat(AbuseGate.inspect("ㅅㅂ")).contains(Category.PROFANITY_ONLY);      // 자모 우회
        assertThat(AbuseGate.inspect("ㅅㅂㅅㅂㅅㅂ")).contains(Category.PROFANITY_ONLY); // 반복 도배
        assertThat(AbuseGate.inspect("병신")).contains(Category.PROFANITY_ONLY);
    }

    @Test
    @DisplayName("상담사를 향한 명령형 공격은 DIRECTED_ABUSE 로 잡는다 — PROFANITY_ONLY 보다 우선")
    void detectsDirectedAbuse() {
        assertThat(AbuseGate.inspect("닥쳐")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("꺼져")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("씨발 닥쳐")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("ㄷㅊ")).contains(Category.DIRECTED_ABUSE);
    }

    @Test
    @DisplayName("2인칭 어절 + 욕 조합도 DIRECTED_ABUSE 로 잡는다 — 어절 정확일치라 오탐 없이")
    void detectsSecondPersonAbuse() {
        assertThat(AbuseGate.inspect("너 병신이냐")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("니가 뭔데 씨발")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("당신 진짜 좆같아")).contains(Category.DIRECTED_ABUSE);
        assertThat(AbuseGate.inspect("넌 개새끼야")).contains(Category.DIRECTED_ABUSE);
    }

    @Test
    @DisplayName("2인칭처럼 보이는 정상 어절은 오탐하지 않는다 — 어절 정확일치의 핵심")
    void doesNotFlagSecondPersonLookalikes() {
        // '너무'·'어머니'는 어절 자체가 2인칭 대명사와 다르다 — 욕이 섞여도 지목이 아니다.
        assertThat(AbuseGate.inspect("너무 힘들어서 씨발 소리가 절로 나왔어")).isEmpty();
        assertThat(AbuseGate.inspect("어머니가 아파서 속상해요")).isEmpty();
        // 2인칭 어절이 있어도 욕이 없으면 지목 공격이 아니다.
        assertThat(AbuseGate.inspect("너를 생각하면 마음이 복잡해")).isEmpty();
        assertThat(AbuseGate.inspect("당신은 어떻게 지내요")).isEmpty();
    }

    @Test
    @DisplayName("감정 표출형 욕(내용이 있는 답변)은 통과시킨다 — 앱의 핵심 콘텐츠")
    void doesNotFlagVenting() {
        assertThat(AbuseGate.inspect("씨발 그 사람 때문에 하루종일 진짜 힘들었어")).isEmpty();
        assertThat(AbuseGate.inspect("아 진짜 개짜증나 오늘 발표 망했어")).isEmpty();
        assertThat(AbuseGate.inspect("씨발 왜 나한테만 이러는지 모르겠어")).isEmpty();
    }

    @Test
    @DisplayName("정상 답변은 오탐하지 않는다 — '너무'·'어머니'처럼 욕과 겹치는 부분 문자열")
    void doesNotFlagGenuineAnswers() {
        assertThat(AbuseGate.inspect("오늘 너무 힘들었어요")).isEmpty();       // '너무' ≠ 2인칭 '너'
        assertThat(AbuseGate.inspect("어머니랑 통화하다 울컥했어요")).isEmpty();  // '어머니' ≠ '니'
        assertThat(AbuseGate.inspect("미친 듯이 바빴던 하루였어요")).isEmpty();   // 단독 '미친' 은 제외
        assertThat(AbuseGate.inspect("발표를 잘 못해서 속상했어요")).isEmpty();
        assertThat(AbuseGate.inspect("ㅋㅋㅋ 그냥 웃겼어요")).isEmpty();          // 웃음은 욕 아님
    }

    @Test
    @DisplayName("웃음·울음 자모 반복은 통과시킨다 — 정규화가 모음을 지우고, 자음은 사전에 없다")
    void doesNotFlagEmoteRepetition() {
        assertThat(AbuseGate.inspect("ㅋㅋㅋㅋㅋ")).isEmpty();       // 자음이나 욕 사전에 없음
        assertThat(AbuseGate.inspect("ㅠㅠㅠㅠㅠㅠ")).isEmpty();     // 모음이라 정규화가 지움
        assertThat(AbuseGate.inspect("ㅎㅎㅎ")).isEmpty();
        assertThat(AbuseGate.inspect("ㅜㅜㅜ")).isEmpty();
    }
}
