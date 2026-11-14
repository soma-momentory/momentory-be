-- 감정 체계를 10종에서 대표 감정 12종으로 바꾼다. 평문으로 저장된 옛 값을 새 값으로 옮긴다.
--
--   happy → joy · depressed → sad · frustrated → angry · stuck → confused
--   lethargic → exhausted · tired → exhausted
--   (anxious · angry · proud · calm 은 그대로)
--
-- 대상 컬럼과 저장 형식:
--   diaries.primary_emotion            열거 상수 이름(대문자)
--   diaries.emotions                   감정 키 CSV(소문자)
--   action_cards.emotions              감정 키 CSV(소문자)
--   retrospect_topic_emotions.emotion  열거 상수 이름(대문자)
--
-- JSON 으로 저장된 값(retrospects.state_json · diaries.events_json)은 여기서 고치지 않는다 —
-- 읽는 쪽이 옛 이름을 새 감정으로 받아들인다(EmotionJsonModule). schedules.emotion 은 별도
-- 체계(ScheduleEmotion)라 대상이 아니다.

-- 1) 일기 대표 감정
UPDATE diaries
SET primary_emotion = CASE primary_emotion
        WHEN 'HAPPY' THEN 'JOY'
        WHEN 'DEPRESSED' THEN 'SAD'
        WHEN 'FRUSTRATED' THEN 'ANGRY'
        WHEN 'STUCK' THEN 'CONFUSED'
        WHEN 'LETHARGIC' THEN 'EXHAUSTED'
        WHEN 'TIRED' THEN 'EXHAUSTED'
    END
WHERE primary_emotion IN ('HAPPY', 'DEPRESSED', 'FRUSTRATED', 'STUCK', 'LETHARGIC', 'TIRED');

-- 2) 감정 키 CSV — 키마다 옮긴 뒤, 합쳐져 겹친 것은 먼저 나온 자리 하나만 남긴다(순서 유지).
UPDATE diaries d
SET emotions = (
    SELECT string_agg(m.emotion_key, ',' ORDER BY m.first_position)
    FROM (
        SELECT x.emotion_key, min(x.position) AS first_position
        FROM (
            SELECT CASE btrim(t.emotion_key)
                       WHEN 'happy' THEN 'joy'
                       WHEN 'depressed' THEN 'sad'
                       WHEN 'frustrated' THEN 'angry'
                       WHEN 'stuck' THEN 'confused'
                       WHEN 'lethargic' THEN 'exhausted'
                       WHEN 'tired' THEN 'exhausted'
                       ELSE btrim(t.emotion_key)
                   END AS emotion_key,
                   t.position
            FROM unnest(string_to_array(d.emotions, ',')) WITH ORDINALITY AS t(emotion_key, position)
            WHERE btrim(t.emotion_key) <> ''
        ) x
        GROUP BY x.emotion_key
    ) m
)
WHERE d.emotions ~ '(happy|depressed|frustrated|stuck|lethargic|tired)';

UPDATE action_cards c
SET emotions = (
    SELECT string_agg(m.emotion_key, ',' ORDER BY m.first_position)
    FROM (
        SELECT x.emotion_key, min(x.position) AS first_position
        FROM (
            SELECT CASE btrim(t.emotion_key)
                       WHEN 'happy' THEN 'joy'
                       WHEN 'depressed' THEN 'sad'
                       WHEN 'frustrated' THEN 'angry'
                       WHEN 'stuck' THEN 'confused'
                       WHEN 'lethargic' THEN 'exhausted'
                       WHEN 'tired' THEN 'exhausted'
                       ELSE btrim(t.emotion_key)
                   END AS emotion_key,
                   t.position
            FROM unnest(string_to_array(c.emotions, ',')) WITH ORDINALITY AS t(emotion_key, position)
            WHERE btrim(t.emotion_key) <> ''
        ) x
        GROUP BY x.emotion_key
    ) m
)
WHERE c.emotions ~ '(happy|depressed|frustrated|stuck|lethargic|tired)';

-- 3) 토픽 × 감정 — 옮기면 같은 토픽에서 같은 감정이 되는 행(피곤함+무기력, 화남+답답함)은
--    uk_rte_topic_emotion 에 걸리므로, id 가 큰 쪽을 먼저 지운 뒤 옮긴다.
DELETE FROM retrospect_topic_emotions a
USING retrospect_topic_emotions b
WHERE a.topic_id = b.topic_id
  AND a.id > b.id
  AND CASE a.emotion
          WHEN 'HAPPY' THEN 'JOY'
          WHEN 'DEPRESSED' THEN 'SAD'
          WHEN 'FRUSTRATED' THEN 'ANGRY'
          WHEN 'STUCK' THEN 'CONFUSED'
          WHEN 'LETHARGIC' THEN 'EXHAUSTED'
          WHEN 'TIRED' THEN 'EXHAUSTED'
          ELSE a.emotion
      END
    = CASE b.emotion
          WHEN 'HAPPY' THEN 'JOY'
          WHEN 'DEPRESSED' THEN 'SAD'
          WHEN 'FRUSTRATED' THEN 'ANGRY'
          WHEN 'STUCK' THEN 'CONFUSED'
          WHEN 'LETHARGIC' THEN 'EXHAUSTED'
          WHEN 'TIRED' THEN 'EXHAUSTED'
          ELSE b.emotion
      END;

UPDATE retrospect_topic_emotions
SET emotion = CASE emotion
        WHEN 'HAPPY' THEN 'JOY'
        WHEN 'DEPRESSED' THEN 'SAD'
        WHEN 'FRUSTRATED' THEN 'ANGRY'
        WHEN 'STUCK' THEN 'CONFUSED'
        WHEN 'LETHARGIC' THEN 'EXHAUSTED'
        WHEN 'TIRED' THEN 'EXHAUSTED'
    END
WHERE emotion IN ('HAPPY', 'DEPRESSED', 'FRUSTRATED', 'STUCK', 'LETHARGIC', 'TIRED');
