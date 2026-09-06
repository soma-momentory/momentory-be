package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 대화에서 뽑은 사건 한 건 (모델 비교 계획 §3.1) — 최대 2개.
 *
 * <p>{@code evidence} 는 <b>그 사건에 속하는 사용자 발화 번호 전체</b>(배정)다. 감정의 근거 문장보다
 * 넓다 — {@link ExtractedEmotion#evidenceIds()} 가 이 집합의 부분집합이 된다.
 *
 * <p>이 집합이 모델 비교의 <b>사건 정렬 기준</b>이다(계획 §7.1): 예측 사건과 정답 사건을 발화 번호
 * 집합의 IoU 로 비교해 짝짓는다. 근거 없이 뽑힌 사건은 매칭에 실패해 FP 로 잡히는데, 이는 환각 사건에
 * 대한 의도된 페널티다.
 *
 * @param id       1부터 매기는 사건 번호 — {@link ExtractedEmotion#eventId()} 가 참조한다.
 * @param label    그 사건을 부르는 <b>짧은 이름</b>("면접 스터디"). 토픽 누적·집계의 단위라 날마다
 *                 같은 일이 같은 이름으로 쌓여야 한다 — 문장이 아니라 이름이어야 하는 이유다.
 *                 비면 {@link #summary()} 로 대신한다.
 * @param summary  사건 요약 한 줄 — Event Summary Score 의 채점 대상
 * @param evidence 그 사건에 속하는 사용자 발화 번호(오름차순, 중복 제거)
 */
public record ExtractedEvent(int id, String label, String summary, List<Integer> evidence) {

    public ExtractedEvent {
        label = label == null || label.isBlank() ? null : label.strip();
        summary = summary == null || summary.isBlank() ? null : summary.strip();
        evidence = normalize(evidence);
    }

    /** 토픽으로 쓸 이름 — 짧은 label 이 없으면 요약으로 대신한다. */
    public String topicLabel() {
        return label != null ? label : summary;
    }

    private static List<Integer> normalize(List<Integer> ids) {
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
}
