-- 수집 중 진행 · 처치 확인 — 표본이 얼마나 찼는지, 실험군이 실제로 다르게 동작하는지 본다. 00_setup.sql 을 먼저 실행한다.
-- 처치 확인에서 두 집단이 같게 동작하면 결과 차이를 처치 효과로 볼 수 없다(문서 5장 「처치 확인」).

-- ── 1. 표본 ── 목표: 분석 대상 세션(처음 조건을 충족한 세션부터) 집단당 약 120(사용자 묶음 반영 시 약 190).
SELECT arm,
       analysis_target,
       count(*) FILTER (WHERE condition_met) AS condition_met_sessions,
       count(*) AS sessions,
       count(DISTINCT user_id) AS users,
       count(*) FILTER (WHERE dropout_status = 'PENDING') AS still_today,
       min(start_day) AS first_day,
       max(start_day) AS last_day
FROM sm_sessions
GROUP BY arm, analysis_target
ORDER BY arm, analysis_target DESC;

-- ── 2. 처치 확인 ── 분석 대상(두 집단 · 처음 조건 충족 이후) 세션만.
SELECT arm,
       count(*) AS sessions,
       -- 이 세션을 시작할 때 조건을 충족한 비율 — 실험군에서는 성향이 실제로 반영된 세션의 비율이다.
       round(100.0 * count(*) FILTER (WHERE condition_met) / count(*), 1) AS condition_met_pct,
       count(*) FILTER (WHERE explore_questions > 0) AS sessions_with_question,
       -- 첫 탐색 질문이 선택형이었던 비율(탐색 질문이 있는 세션 기준)
       round(100.0 * count(*) FILTER (WHERE first_question_as_choices)
             / NULLIF(count(*) FILTER (WHERE explore_questions > 0), 0), 1) AS first_choice_pct,
       -- 세션당 탐색 질문 수
       round(avg(explore_questions), 2) AS questions_per_session,
       -- 선택형 질문 비율(질문 단위)
       round(100.0 * sum(choice_questions) / NULLIF(sum(explore_questions), 0), 1) AS choice_question_pct,
       -- 재해석 방침이 한 번 이상 적용된 세션 비율
       round(100.0 * count(*) FILTER (WHERE reframe_turns > 0) / count(*), 1) AS reframe_session_pct,
       round(100.0 * count(*) FILTER (WHERE first_turn_listening) / count(*), 1) AS first_listen_pct,
       round(avg(feeling_questions), 2) AS feeling_questions_per_session
FROM sm_session_metrics
WHERE arm IN ('TREATMENT', 'CONTROL') AND analysis_target
GROUP BY arm
ORDER BY arm DESC;

-- ── 3. 처음 도출된 시작 성향 분포 ── 처치가 닿기 전의 값이라 두 집단에서 비슷해야 한다(배정이 성향과 얽히지 않았는지).
SELECT arm,
       count(*) FILTER (WHERE derived_self_led) AS derived_self_led,
       count(*) FILTER (WHERE derived_other_view) AS derived_other_view,
       count(*) FILTER (WHERE derived_listen) AS derived_listen,
       count(*) FILTER (WHERE derived_gentle_emotion) AS derived_gentle_emotion,
       count(*) AS users_with_condition
FROM sm_first_conditions
WHERE arm IN ('TREATMENT', 'CONTROL')
GROUP BY arm
ORDER BY arm DESC;
