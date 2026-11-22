-- 대화 전략 실험 집계 — 공통 임시 뷰.
--
-- 다른 파일(10 · 20 · 30 · 40)은 모두 이 뷰를 읽는다. 같은 DB 연결(psql 세션 · IntelliJ 콘솔)에서 이 파일을 먼저 실행한다.
-- 임시 뷰라 연결을 닫으면 사라지고, 테이블에는 아무것도 쓰지 않는다.
--
-- 지표 정의는 Notion 「대화 전략 선택을 통한 효율적인 감정 탐색 기술」 5장을 따른다.
--   탐색 질문   = retrospect_turn_judgments.question_sent = true 인 턴(선택형 포함, 공통 첫 질문 · 다른 기억 · 마무리 제안 제외)
--   그 반응     = 같은 회고의 바로 다음 일기 턴(diary_turn + 1) 판단의 reaction
--   응답 곤란   = struggle_to_answer
--   저항        = refuse_question · no_answer_or_topic_change · argue
--                 단, 답변이 새 이야기의 시작이었다면(new_topic, V50) 「다른 얘기로 넘어감」은 저항이 아니다(NEW_TOPIC).
--   확신이 기준(min_confidence) 미만인 반응은 어느 쪽에도 세지 않는다(문서 2장 「신호 제외」).

DROP VIEW IF EXISTS pg_temp.sm_params CASCADE;

-- ── 1. 분석 조건 ── 실행 전에 고친다.
CREATE TEMP VIEW sm_params AS
SELECT
    -- 이 시각 이후 시작한 회고만 본다. 재려는 서버에 V55(세 성향 시작값)가 배포된 시각으로 바꾼다.
    TIMESTAMPTZ '2026-11-17 00:00:00+09' AS since,
    -- 이 시각 이전에 시작한 회고만 본다(수집 마감). 열어 두려면 그대로 둔다.
    TIMESTAMPTZ '9999-12-31 00:00:00+09' AS until,
    0.5::float8 AS min_confidence,
    'always-v2'::text AS disposition_version;

-- ── 2. 처음 완료 회고 이력이 생긴 대화 ── 사용자 · 집단마다 한 줄. 답하기 전에 떠난 대화도 시작할 때 조건을 충족했으면 센다.
CREATE TEMP VIEW sm_first_conditions AS
SELECT DISTINCT ON (o.user_id, o.apply_arm)
       o.user_id,
       o.apply_arm AS arm,
       r.created_at AS first_condition_at,
       o.derived_self_led,
       o.derived_other_view,
       o.derived_listen,
       o.derived_gentle_emotion
FROM retrospect_disposition_observations o
JOIN retrospects r ON r.id = o.retrospect_id
CROSS JOIN sm_params p
WHERE o.derived_history_sessions > 0
  AND o.disposition_version = p.disposition_version
  AND r.created_at >= p.since AND r.created_at < p.until
ORDER BY o.user_id, o.apply_arm, r.created_at, r.id;

-- ── 3. 대상 세션 ── 전략 대화로 진행된 회고 한 건이 한 줄.
CREATE TEMP VIEW sm_sessions AS
WITH strategy_turns AS (
    SELECT retrospect_id,
           count(*) AS logged_turns,
           max(diary_turn) AS max_diary_turn,
           string_agg(DISTINCT policy_version, ',') AS policy_versions,
           string_agg(DISTINCT questions_version, ',') AS questions_versions,
           bool_or(generation_failed) AS had_generation_failure,
           bool_or(status = 'FAILED') AS had_judge_failure
    FROM retrospect_turn_judgments
    WHERE policy_version IS NOT NULL
    GROUP BY retrospect_id
),
base AS (
    SELECT r.id AS retrospect_id,
           r.user_id,
           r.status,
           r.end_reason,
           r.created_at AS started_at,
           r.completed_at,
           r.last_user_turn_at,
           r.resume_count,
           ((r.created_at AT TIME ZONE 'Asia/Seoul') - INTERVAL '4 hours')::date AS start_day,
           COALESCE(t.logged_turns, 0) AS logged_turns,
           COALESCE(t.max_diary_turn, 0) AS max_diary_turn,
           t.policy_versions,
           t.questions_versions,
           COALESCE(t.had_generation_failure, FALSE) AS had_generation_failure,
           COALESCE(t.had_judge_failure, FALSE) AS had_judge_failure,
           COALESCE(o.apply_arm, 'NONE') AS arm,
           o.id IS NOT NULL AS has_observation,
           -- 과거 이력 보유 — 특별한 신호가 없어도 세 차원을 읽어 기본값으로 반영한다(두 군 공통).
           COALESCE(o.derived_history_sessions > 0, FALSE) AS condition_met,
           COALESCE(o.derived_self_led, FALSE) AS derived_self_led,
           COALESCE(o.derived_other_view, FALSE) AS derived_other_view,
           COALESCE(o.derived_listen, FALSE) AS derived_listen,
           COALESCE(o.derived_gentle_emotion, FALSE) AS derived_gentle_emotion
    FROM retrospects r
    LEFT JOIN strategy_turns t ON t.retrospect_id = r.id
    LEFT JOIN retrospect_disposition_observations o ON o.retrospect_id = r.id
    CROSS JOIN sm_params p
    WHERE r.created_at >= p.since AND r.created_at < p.until
      AND (o.disposition_version = p.disposition_version OR o.id IS NULL)
      AND (t.retrospect_id IS NOT NULL OR o.disposition_version = p.disposition_version)
),
dated AS (
    SELECT b.*,
           ((b.start_day + TIME '04:00') AT TIME ZONE 'Asia/Seoul') AS day_start,
           (((b.start_day + 1) + TIME '04:00') AT TIME ZONE 'Asia/Seoul') AS day_end
    FROM base b
),
flagged AS (
    SELECT d.*,
           -- 위기 안내로 멈춤: 끝나지 않았고(종료 사유 없음) 안전 중단 상태이거나 위기 기록이 있다.
           (d.end_reason IS NULL AND (d.status = 'ENDED' OR EXISTS (
               SELECT 1 FROM safety_events s WHERE s.retrospect_id = d.retrospect_id))) AS safety_stopped,
           -- 같은 하루에 다른 경로로 쓴 일기가 있다 — 이 회고는 완료가 막혀 진행 중으로 남는다.
           EXISTS (SELECT 1 FROM diaries x
                   WHERE x.user_id = d.user_id
                     AND x.retrospect_id IS DISTINCT FROM d.retrospect_id
                     AND x.created_at >= d.day_start AND x.created_at < d.day_end) AS other_diary_same_day,
           -- 정상 정리(마무리 수락 · 다른 기억 없음 · 턴 상한) 또는 사용자 요청으로, 시작한 하루 안에 끝났다.
           (d.end_reason IN ('WRAP_UP_ACCEPTED', 'NO_OTHER_MEMORY', 'TURN_CAP', 'USER_REQUEST')
               AND d.completed_at < d.day_end) AS completed_in_day
    FROM dated d
)
SELECT f.*,
       fc.first_condition_at,
       -- 분석 대상: 처음 완료 회고 이력이 생긴 세션과 그 뒤의 모든 세션. 세션마다 조건으로 거르면, 처치로 저항이 줄어든 사용자가
       -- 분석에서 빠져 집단 비교가 치우친다.
       COALESCE(f.started_at >= fc.first_condition_at, FALSE) AS analysis_target,
       CASE
           WHEN f.had_generation_failure OR f.had_judge_failure THEN 'EXCLUDED_SYSTEM_ERROR'
           WHEN f.end_reason IN ('ABUSE_STOPPED', 'LEGACY_EXPLORATION') THEN 'EXCLUDED_ABUSE_OR_LEGACY'
           WHEN f.safety_stopped THEN 'EXCLUDED_SAFETY'
           WHEN f.completed_in_day THEN 'COMPLETED'
           WHEN now() < f.day_end THEN 'PENDING'          -- 아직 그 하루가 끝나지 않았다
           WHEN f.other_diary_same_day THEN 'EXCLUDED_OTHER_DIARY'
           ELSE 'DROPOUT'                                 -- 하루가 지나도록 정상 정리 · 사용자 요청으로 끝나지 않았다
       END AS dropout_status
FROM flagged f
LEFT JOIN sm_first_conditions fc ON fc.user_id = f.user_id AND fc.arm = f.arm;

-- ── 4. 탐색 질문과 그 반응 ── 실제로 나간 탐색 질문 하나가 한 줄.
CREATE TEMP VIEW sm_questions AS
WITH turns AS (
    SELECT j.*,
           lead(j.diary_turn) OVER w AS next_diary_turn,
           lead(j.status) OVER w AS next_status,
           lead(j.reaction) OVER w AS next_reaction,
           lead(j.reaction_confidence) OVER w AS next_reaction_confidence,
           lead(j.new_topic) OVER w AS next_new_topic
    FROM retrospect_turn_judgments j
    WHERE j.retrospect_id IN (SELECT retrospect_id FROM sm_sessions)
    WINDOW w AS (PARTITION BY j.retrospect_id ORDER BY j.diary_turn, j.id)
)
SELECT t.retrospect_id,
       t.user_id,
       t.diary_turn,
       t.strategy_type,
       t.question_as_choices,
       t.generation_failed,
       t.asked_feeling,
       row_number() OVER (PARTITION BY t.retrospect_id ORDER BY t.diary_turn, t.id) AS question_no,
       CASE WHEN t.next_diary_turn = t.diary_turn + 1 THEN t.next_reaction END AS reaction,
       CASE WHEN t.next_diary_turn = t.diary_turn + 1 THEN t.next_reaction_confidence END AS reaction_confidence,
       CASE WHEN t.next_diary_turn = t.diary_turn + 1 THEN t.next_new_topic END AS answer_new_topic,
       CASE
           WHEN t.next_diary_turn IS DISTINCT FROM t.diary_turn + 1 THEN 'NO_FOLLOWUP'   -- 질문 뒤에 답이 없다(끊김 등)
           WHEN t.next_status = 'FAILED' OR t.next_reaction IS NULL THEN 'JUDGE_FAILED'
           WHEN t.next_reaction_confidence < p.min_confidence THEN 'LOW_CONFIDENCE'
           WHEN t.next_reaction = 'struggle_to_answer' THEN 'STRUGGLE'
           -- 질문에 답하지 않고 새 이야기를 꺼냈다 — 전략도 이 턴을 저항으로 세지 않는다.
           WHEN t.next_reaction = 'no_answer_or_topic_change' AND t.next_new_topic IS TRUE THEN 'NEW_TOPIC'
           WHEN t.next_reaction IN ('refuse_question', 'no_answer_or_topic_change', 'argue') THEN 'RESISTANCE'
           ELSE 'OTHER'
       END AS response_class
FROM turns t
CROSS JOIN sm_params p
WHERE t.question_sent IS TRUE;

-- ── 4. 세션별 지표 ── 대상 세션 한 건이 한 줄. 통계 스크립트(신뢰구간 · 사용자 묶음)에 넘길 표이기도 하다.
CREATE TEMP VIEW sm_session_metrics AS
WITH q AS (
    SELECT retrospect_id,
           count(*) AS explore_questions,
           count(*) FILTER (WHERE question_as_choices) AS choice_questions,
           count(*) FILTER (WHERE response_class IN ('STRUGGLE', 'RESISTANCE', 'NEW_TOPIC', 'OTHER')) AS judged_questions,
           count(*) FILTER (WHERE response_class = 'STRUGGLE') AS struggle_questions,
           count(*) FILTER (WHERE response_class = 'RESISTANCE') AS resistance_questions,
           count(*) FILTER (WHERE response_class = 'NO_FOLLOWUP') AS unanswered_questions,
           bool_or(question_as_choices) FILTER (WHERE question_no = 1) AS first_question_as_choices
    FROM sm_questions
    GROUP BY retrospect_id
),
s AS (
    SELECT retrospect_id,
           count(*) FILTER (WHERE strategy_type = 'REFRAME') AS reframe_turns,
           count(*) FILTER (WHERE strategy_type = 'YIELD') AS yield_turns,
           count(*) FILTER (WHERE asked_feeling) AS feeling_questions,
           bool_or(strategy_type = 'REFLECT') FILTER (WHERE diary_turn = 1) AS first_turn_listening
    FROM retrospect_turn_judgments
    WHERE retrospect_id IN (SELECT retrospect_id FROM sm_sessions)
    GROUP BY retrospect_id
)
SELECT ss.retrospect_id,
       ss.user_id,
       ss.arm,
       ss.condition_met,
       ss.analysis_target,
       ss.start_day,
       ss.policy_versions,
       ss.dropout_status,
       COALESCE(q.explore_questions, 0) AS explore_questions,
       COALESCE(q.choice_questions, 0) AS choice_questions,
       COALESCE(q.judged_questions, 0) AS judged_questions,
       COALESCE(q.struggle_questions, 0) AS struggle_questions,
       COALESCE(q.resistance_questions, 0) AS resistance_questions,
       COALESCE(q.unanswered_questions, 0) AS unanswered_questions,
       q.first_question_as_choices,
       COALESCE(s.reframe_turns, 0) AS reframe_turns,
       COALESCE(s.yield_turns, 0) AS yield_turns,
       COALESCE(s.feeling_questions, 0) AS feeling_questions,
       COALESCE(s.first_turn_listening, FALSE) AS first_turn_listening,
       COALESCE(q.struggle_questions, 0) > 0 AS had_struggle,
       COALESCE(q.resistance_questions, 0) > 0 AS had_resistance,
       ss.dropout_status = 'DROPOUT' AS dropped_out,
       ss.dropout_status IN ('COMPLETED', 'DROPOUT') AS dropout_evaluable
FROM sm_sessions ss
LEFT JOIN q ON q.retrospect_id = ss.retrospect_id
LEFT JOIN s ON s.retrospect_id = ss.retrospect_id;
