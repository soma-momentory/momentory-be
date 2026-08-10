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
    @DisplayName("[알려진 오탐] 부분일치라 '손목'이 든 무해한 문장도 imminent 로 잡힌다")
    void knownFalsePositive() {
        // 원본 docstring 이 밝힌 대로 키워드 목록은 임상 검수 전 시드다.
        // 고치는 게 아니라 '지금 이렇다'를 고정해 두는 테스트다 — 목록 교체 시 여기가 깨져야 한다.
        assertThat(policy.scan("손목이 아파요").level()).isEqualTo(SafetyLevel.IMMINENT);
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
