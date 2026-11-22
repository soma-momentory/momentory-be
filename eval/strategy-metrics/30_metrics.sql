-- 성능 지표 — 집단별 응답 곤란률 · 저항률 · 미완료 이탈률. 00_setup.sql 을 먼저 실행한다.
--
-- 응답 곤란 · 저항은 Jev 판단으로 센 「자동 집계」다. 문서 5장의 주 판정은 집단을 모르는 사람 평가자가 하고,
-- 자동 집계는 사람 판정과 대조한 뒤 보조로만 쓴다. 감정 명료화율은 사람 판정만 있어 여기에 없다.
-- 신뢰구간과 사용자 묶음 분석은 3번 표를 내보내 통계 스크립트로 한다.

-- ── 1. 응답 곤란률 · 저항률 ── 분석 대상(두 집단 · 처음 조건을 충족한 세션부터) 세션.
--   세션 단위         : 한 번 이상 나온 세션 ÷ 전체 세션(문서의 정의)
--   질문 있는 세션    : 한 번 이상 나온 세션 ÷ 탐색 질문이 있는 세션
--   질문 단위         : 그 반응이 뒤따른 탐색 질문 ÷ 실제 탐색 질문
--   세션 단위만 낮아지면 「질문이 줄어서 낮아졌다」로 적는다.
SELECT arm,
       count(*) AS sessions,
       count(*) FILTER (WHERE explore_questions > 0) AS sessions_with_question,
       sum(explore_questions) AS questions,

       count(*) FILTER (WHERE had_struggle) AS struggle_sessions,
       round(100.0 * count(*) FILTER (WHERE had_struggle) / count(*), 1) AS struggle_rate_session,
       round(100.0 * count(*) FILTER (WHERE had_struggle)
             / NULLIF(count(*) FILTER (WHERE explore_questions > 0), 0), 1) AS struggle_rate_questioned,
       round(100.0 * sum(struggle_questions) / NULLIF(sum(explore_questions), 0), 1) AS struggle_rate_question,

       count(*) FILTER (WHERE had_resistance) AS resistance_sessions,
       round(100.0 * count(*) FILTER (WHERE had_resistance) / count(*), 1) AS resistance_rate_session,
       round(100.0 * count(*) FILTER (WHERE had_resistance)
             / NULLIF(count(*) FILTER (WHERE explore_questions > 0), 0), 1) AS resistance_rate_questioned,
       round(100.0 * sum(resistance_questions) / NULLIF(sum(explore_questions), 0), 1) AS resistance_rate_question
FROM sm_session_metrics
WHERE arm IN ('TREATMENT', 'CONTROL') AND analysis_target
GROUP BY arm
ORDER BY arm DESC;

-- ── 2. 미완료 이탈률 ── 이탈 ÷ (완료 + 이탈). 안전 중단 · 악용 중단 · 같은 날 다른 일기 · 아직 하루가 안 끝난 세션은
--   분모에서 빼고 따로 센다. 시스템 오류가 낀 이탈은 10_check_logging.sql 4번으로 따로 본다.
SELECT arm,
       count(*) FILTER (WHERE dropout_evaluable) AS evaluable_sessions,
       count(*) FILTER (WHERE dropped_out) AS dropouts,
       round(100.0 * count(*) FILTER (WHERE dropped_out)
             / NULLIF(count(*) FILTER (WHERE dropout_evaluable), 0), 1) AS dropout_rate,
       count(*) FILTER (WHERE dropout_status = 'EXCLUDED_SAFETY') AS excluded_safety,
       count(*) FILTER (WHERE dropout_status = 'EXCLUDED_ABUSE_OR_LEGACY') AS excluded_abuse_or_legacy,
       count(*) FILTER (WHERE dropout_status = 'EXCLUDED_OTHER_DIARY') AS excluded_other_diary,
       count(*) FILTER (WHERE dropout_status = 'EXCLUDED_SYSTEM_ERROR') AS excluded_system_error,
       count(*) FILTER (WHERE dropout_status = 'PENDING') AS pending_today
FROM sm_session_metrics
WHERE arm IN ('TREATMENT', 'CONTROL') AND analysis_target
GROUP BY arm
ORDER BY arm DESC;

-- ── 3. 통계 스크립트로 넘길 세션별 표 ── CSV 로 내보낸다(psql: \copy (...) TO 'sessions.csv' CSV HEADER).
--   사용자 id 는 묶음 분석에만 쓰고 결과 문서에는 남기지 않는다.
SELECT retrospect_id,
       user_id,
       arm,
       condition_met,
       start_day,
       policy_versions,
       explore_questions,
       choice_questions,
       struggle_questions,
       resistance_questions,
       had_struggle,
       had_resistance,
       dropout_status,
       dropped_out,
       dropout_evaluable
FROM sm_session_metrics
WHERE arm IN ('TREATMENT', 'CONTROL') AND analysis_target
ORDER BY user_id, start_day, retrospect_id;
