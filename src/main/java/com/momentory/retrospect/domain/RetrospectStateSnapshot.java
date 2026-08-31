package com.momentory.retrospect.domain;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * {@link RetrospectState} 의 영속 필드를 담는 순수 스냅샷 — DB의 {@code state_json} 에 직렬화된다
 * (채팅흐름_v2 상태 구조).
 *
 * <p>프레임워크 의존이 없어 도메인 순수성을 지키며, 직렬화(Jackson)는 인프라의 코덱이 이 record 를
 * 대상으로 수행한다. 열거형은 이름으로 직렬화되므로 상수 이름을 바꾸면 기존 스냅샷과 호환이 깨진다.
 */
public record RetrospectStateSnapshot(
        String id,
        String nickname,
        Long scheduleId,
        String schedule,
        Emotion scheduleEmotion,
        String interest,
        List<String> restMethods,
        Phase phase,
        Phase heldFrom,
        int reasks,
        int abuseStreak,
        // 일기 작성 슬롯 (diary_chat)
        int diaryTurn,
        String event,
        List<String> secondaryEvents,
        List<ExtractedEmotion> emotions,
        boolean emotionSeen,
        String meaning,
        String diaryDraft,
        boolean diaryUserEnded,
        // 감정 탐색 슬롯 (emotion_exploration)
        boolean explorationEntered,
        int explorationTurn,
        List<Emotion> confirmedEmotions,
        List<Need> needs,
        String desiredState,
        String smallAction,
        // 기록
        List<Message> messages,
        SafetySnapshot safety,
        List<Choice> lastOptions,
        int messageSeq) {

    /** 안전 상태 스냅샷 — 누적 레벨·플래그·마지막 플래그 메시지 id. */
    public record SafetySnapshot(SafetyLevel level, List<String> flags, String lastFlaggedMsgId) {
    }
}
