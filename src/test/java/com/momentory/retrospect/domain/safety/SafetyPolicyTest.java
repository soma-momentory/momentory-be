package com.momentory.retrospect.domain.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.momentory.retrospect.domain.safety.SafetyPolicy.ScanResult;

/**
 * 기대값은 원본 파이썬 {@code safety.scan()} 을 직접 실행해 뽑았다.
 * 임의로 지어낸 값이 아니라 원본이 실제로 내는 값이다.
 */
class SafetyPolicyTest {

    private final SafetyPolicy policy = new SafetyPolicy();

    static List<Arguments> goldenScans() {
        return List.of(
                Arguments.of("", SafetyLevel.NONE, List.of()),
                Arguments.of("오늘은 그냥 평범했어요", SafetyLevel.NONE, List.of()),
                Arguments.of("죽고 싶다는 생각이 들었어요", SafetyLevel.IMMINENT,
                        List.of(SafetyPolicy.FLAG_CRISIS)),
                Arguments.of("자살 생각이 났어", SafetyLevel.IMMINENT,
                        List.of(SafetyPolicy.FLAG_CRISIS)),
                Arguments.of("그냥 살기 싫어", SafetyLevel.RISK,
                        List.of(SafetyPolicy.FLAG_CRISIS)),
                Arguments.of("다 끝내고 싶어", SafetyLevel.RISK,
                        List.of(SafetyPolicy.FLAG_CRISIS)),
                Arguments.of("너무 힘들어서 못 견디겠어", SafetyLevel.RISK,
                        List.of(SafetyPolicy.FLAG_CRISIS)),
                Arguments.of("씨발 진짜 짜증나", SafetyLevel.CAUTION,
                        List.of(SafetyPolicy.FLAG_PROFANITY)));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @MethodSource("goldenScans")
    @DisplayName("규칙 층 탐지 결과가 원본과 같다")
    void matchesOriginal(String input, SafetyLevel expectedLevel, List<String> expectedFlags) {
        ScanResult result = policy.scan(input);

        assertThat(result.level()).isEqualTo(expectedLevel);
        assertThat(result.flags()).containsExactlyElementsOf(expectedFlags);
    }

    @Test
    @DisplayName("공백을 끼워 넣어 우회해도 잡는다")
    void detectsSpacedOutKeywords() {
        assertThat(policy.scan("죽 고 싶 다").level()).isEqualTo(SafetyLevel.IMMINENT);
    }

    @Test
    @DisplayName("위기 표현과 욕설이 함께 있으면 더 높은 쪽을 취하고 플래그는 둘 다 남는다")
    void mergesCrisisAndProfanity() {
        ScanResult result = policy.scan("시발 죽고 싶어");

        assertThat(result.level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(result.flags())
                .containsExactly(SafetyPolicy.FLAG_CRISIS, SafetyPolicy.FLAG_PROFANITY);
    }

    @Test
    @DisplayName("nullism: null 입력은 빈 문자열과 같게 다룬다")
    void nullIsSafe() {
        assertThat(policy.scan(null).isClean()).isTrue();
    }

    @Test
    @DisplayName("줄바꿈 · 탭 · 전각 공백 · NBSP 를 끼워 넣어도 위기 표현을 잡는다")
    void whitespaceOtherThanSpaceDoesNotHideCrisis() {
        assertThat(policy.scan("죽고\n싶어").level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(policy.scan("죽고\r\n싶어").level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(policy.scan("살기\t싫어").level()).isEqualTo(SafetyLevel.RISK);
        assertThat(policy.scan("사라지고\u3000싶다").level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(policy.scan("다\u00A0끝내고 싶다").level()).isEqualTo(SafetyLevel.RISK);
        assertThat(policy.scan("오늘은\n그냥 피곤했다").isClean()).isTrue();
    }

    @Test
    @DisplayName("관용 표현 · 다른 대상은 위기로 보지 않는다(손목 통증 · 목숨을 걸고 · 번지점프에서 뛰어내릴 때)")
    void idiomsAreNotCrisis() {
        for (String text : List.of("요즘 손목이 너무 아프다.", "손목터널증후군 초기래", "돌아오는 길에 손목 받침대를 샀다",
                "정말 목숨을 걸고 새로고침을 눌렀다.", "진짜 목숨 걸고 새로고침 눌렀다",
                "친구들과 번지점프를 했다. 처음 뛰어내릴 때는 다리가 후들거렸다.")) {
            assertThat(policy.scan(text).isClean()).as(text).isTrue();
        }
    }

    @Test
    @DisplayName("실제 위험 문장은 관용 표현과 섞여 있어도 그대로 imminent 다")
    void realRiskStillImminent() {
        for (String text : List.of("손목을 긋고 싶다", "손목에 상처를 냈다", "손목이 아파도 긋고 싶다",
                "손목이 아프다. 그래도 칼을 들었다", "뛰어내리고 싶다", "옥상에서 뛰어내릴 때를 자꾸 생각한다",
                "번지점프 말고 그냥 다리에서 뛰어내리고 싶다", "목숨을 끊고 싶다", "목숨 걸고 죽어버리고 싶다",
                "목숨이 아깝지 않다", "손목")) {
            assertThat(policy.scan(text).level()).as(text).isEqualTo(SafetyLevel.IMMINENT);
        }
    }

    @Test
    @DisplayName("[알려진 한계] 과거 언급과 문맥으로만 가를 말은 이번에 빼지 않았다 — 임상 검수 뒤에 다룬다")
    void knownLimits() {
        assertThat(policy.scan("작년에는 정말 죽고 싶었다. 지금은 괜찮다").level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(policy.scan("교수님 앞에서 정말 사라지고 싶었다").level()).isEqualTo(SafetyLevel.IMMINENT);
        assertThat(policy.scan("정상 직전에는 너무 힘들어서 포기할 뻔했다").level()).isEqualTo(SafetyLevel.RISK);
    }

    @Nested
    @DisplayName("SafetyLevel")
    class Levels {

        @Test
        void maxTakesHigher() {
            assertThat(SafetyLevel.max(SafetyLevel.CAUTION, SafetyLevel.RISK))
                    .isEqualTo(SafetyLevel.RISK);
            assertThat(SafetyLevel.max(SafetyLevel.IMMINENT, SafetyLevel.NONE))
                    .isEqualTo(SafetyLevel.IMMINENT);
        }

        @Test
        void atLeastComparesByOrder() {
            assertThat(SafetyLevel.RISK.atLeast(SafetyLevel.CAUTION)).isTrue();
            assertThat(SafetyLevel.CAUTION.atLeast(SafetyLevel.RISK)).isFalse();
            assertThat(SafetyLevel.NONE.atLeast(SafetyLevel.NONE)).isTrue();
        }

        @Test
        @DisplayName("알 수 없는 키는 NONE 으로 떨어진다 (원본 폴백과 동일)")
        void unknownKeyFallsBackToNone() {
            assertThat(SafetyLevel.fromKey("nonsense")).isEqualTo(SafetyLevel.NONE);
            assertThat(SafetyLevel.fromKey(null)).isEqualTo(SafetyLevel.NONE);
        }

        @Test
        void riskAndImminentStopRetrospect() {
            assertThat(SafetyLevel.RISK.stopsRetrospect()).isTrue();
            assertThat(SafetyLevel.IMMINENT.stopsRetrospect()).isTrue();
            assertThat(SafetyLevel.CAUTION.stopsRetrospect()).isFalse();
        }
    }

    @Nested
    @DisplayName("Guidance")
    class GuidanceRendering {

        @Test
        @DisplayName("risk/imminent 에만 고정 응답이 있다")
        void onlyForSevereLevels() {
            assertThat(policy.guidanceFor(SafetyLevel.IMMINENT)).isNotNull();
            assertThat(policy.guidanceFor(SafetyLevel.RISK)).isNotNull();
            assertThat(policy.guidanceFor(SafetyLevel.CAUTION)).isNull();
            assertThat(policy.guidanceFor(SafetyLevel.NONE)).isNull();
        }

        @Test
        @DisplayName("렌더 결과가 원본 포맷(제목·빈줄·본문·빈줄·리소스)과 같다")
        void renderMatchesOriginalFormat() {
            String rendered = policy.guidanceFor(SafetyLevel.RISK).render();

            assertThat(rendered).startsWith("잠깐 여기서 함께 멈춰볼게요\n\n");
            assertThat(rendered).contains("\n\n  · 자살예방 상담전화 109 (24시간)");
            assertThat(rendered).contains("  · 경찰 112 · 응급 119");
        }
    }
}
