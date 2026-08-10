package com.momentory.retrospect.domain;

import java.util.List;
import java.util.Map;

import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;

/**
 * {@link RetrospectState} 의 영속 필드를 담는 순수 스냅샷 — DB의 {@code state_json} 에 직렬화된다.
 *
 * <p>파생 필드({@code steps})와 세션 임시 값은 제외한다. 프레임워크 의존이 없어 도메인 순수성을
 * 지키며, 직렬화(Jackson)는 인프라의 코덱이 이 record 를 대상으로 수행한다. 열거형은 이름으로
 * 직렬화되므로 상수 이름을 바꾸면 기존 스냅샷과 호환이 깨진다(주의).
 */
public record RetrospectStateSnapshot(
        String id,
        String nickname,
        String schedule,
        Emotion scheduleEmotion,
        Emotion currentEmotion,
        String interest,
        List<ScheduleItem> pendingSchedules,
        Phase phase,
        RetroMode mode,
        int stepIndex,
        int turn,
        int reasks,
        Map<String, String> answers,
        Map<String, Map<String, Integer>> measures,
        List<Message> messages,
        SafetySnapshot safety,
        String situationSummary,
        List<OptionItem> lastOptions,
        OptionItem chosenAction,
        int messageSeq) {

    /** 안전 상태 스냅샷 — 누적 레벨·플래그·마지막 플래그 메시지 id. */
    public record SafetySnapshot(SafetyLevel level, List<String> flags, String lastFlaggedMsgId) {
    }
}
