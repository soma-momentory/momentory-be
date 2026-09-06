package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 대화에서 추출한 감정 한 건 (모델 비교 계획 §3.1).
 *
 * <p>{@code raw} 는 사용자가 실제로 쓴 표현("무시당한 느낌"), {@code normalized} 는 고정 10종
 * {@link Emotion} 중 하나로 정규화한 값(아직 못 정했으면 {@code null}).
 *
 * <p>근거는 두 가지로 담는다 — {@code evidence} 는 근거가 된 사용자 문장 원문(추정이 아니라 실제
 * 발화), {@code evidenceIds} 는 그 문장의 발화 번호다. 번호가 있어야 채점에서 근거를 기계적으로
 * 대조할 수 있고, 원문이 있어야 라벨링·일기 검증(Unsupported emotion rate)에서 사람이 바로 읽는다.
 * 사건의 발화 배정({@link ExtractedEvent#evidence()})보다 좁은 부분집합이다 — 사건은 넓게(배정),
 * 감정은 좁게(근거).
 *
 * <p>이 값을 만드는 주체는 {@link com.momentory.retrospect.domain.assistant.EmotionExtractor}
 * 하나다 — 지금은 LLM 구현이지만, 나중에 전용 감정 분류 모델로 공급자만 갈아끼울 수 있도록
 * 대화 엔진에서 감정 판단을 이 경계 뒤로 분리한다.
 *
 * @param eventId     이 감정이 붙는 {@link ExtractedEvent#id()} — 특정 사건에 못 붙이면 {@code null}
 * @param intensity   감정 강도 0~4 (0=감정을 말하지 않음 … 4=압도적). 판단 못 하면 {@code null}
 * @param phase       사건 기준 시점. 판단 못 하면 {@code null}
 */
public record ExtractedEmotion(
        Integer eventId,
        String raw,
        Emotion normalized,
        Integer intensity,
        EmotionPhase phase,
        String evidence,
        List<Integer> evidenceIds) {

    /** 강도 눈금의 양 끝 — 범위를 벗어난 값은 버린다(모델이 5나 -1을 줄 수 있다). */
    public static final int MIN_INTENSITY = 0;
    public static final int MAX_INTENSITY = 4;

    public ExtractedEmotion {
        raw = strip(raw);
        evidence = strip(evidence);
        intensity = validIntensity(intensity);
        evidenceIds = normalizeIds(evidenceIds);
    }

    /** 사용자 원문 표현만으로 만든다 — 정규화·부가 정보가 아직 없을 때. */
    public static ExtractedEmotion ofRaw(String raw) {
        return new ExtractedEmotion(null, raw, null, null, null, null, List.of());
    }

    /** 이름을 {@code has-}로 둔다 — {@code is-}면 Jackson 이 record 컴포넌트 {@code normalized} 와 충돌한다. */
    public boolean hasNormalized() {
        return normalized != null;
    }

    /** 눈금 밖 값은 경계로 붙이지 않고 <b>버린다</b> — 모델이 5를 주면 "강도 4"가 아니라 "모름"이다. */
    private static Integer validIntensity(Integer value) {
        if (value == null || value < MIN_INTENSITY || value > MAX_INTENSITY) {
            return null;
        }
        return value;
    }

    private static List<Integer> normalizeIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (Integer id : ids) {
            if (id != null && id > 0) {
                seen.add(id);
            }
        }
        List<Integer> sorted = new ArrayList<>(seen);
        sorted.sort(Integer::compareTo);
        return List.copyOf(sorted);
    }

    private static String strip(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
