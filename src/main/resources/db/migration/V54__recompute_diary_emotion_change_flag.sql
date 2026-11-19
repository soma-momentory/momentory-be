-- 누적 이력이 아닌 현재 선택과 최초 분류의 감정 집합 차이를 저장한다.
-- 사건 연결이 달라도 감정이 남아 있으면 변경으로 세지 않는다.
UPDATE diaries SET emotion_classification_changed = CASE
    WHEN retrospect_id IS NOT NULL AND original_emotion_selection_json IS NOT NULL AND emotion_selection_json IS NOT NULL THEN
        NOT ((jsonb_path_query_array(emotion_selection_json::jsonb, '$.events[*].emotions[*]') ||
              jsonb_path_query_array(emotion_selection_json::jsonb, '$.unlinkedEmotions[*]')) @>
             (jsonb_path_query_array(original_emotion_selection_json::jsonb, '$.events[*].emotions[*]') ||
              jsonb_path_query_array(original_emotion_selection_json::jsonb, '$.unlinkedEmotions[*]')))
    ELSE FALSE
END;
