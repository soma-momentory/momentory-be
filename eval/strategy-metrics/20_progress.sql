-- 수집 중 진행 · 처치 확인 — 표본이 얼마나 찼는지, 실험군이 실제로 다르게 동작하는지 본다. 00_setup.sql 을 먼저 실행한다.
-- 처치 확인에서 두 집단이 같게 동작하면 결과 차이를 처치 효과로 볼 수 없다(문서 5장 「처치 확인」).

-- ── 1. 표본 ── 목표: 조건 충족 세션 집단당 약 120(사용자 묶음 반영 시 약 190).
SELECT arm,
       condition_met,
       count(*) AS sessions,
       count(DISTINCT user_id) AS users,
       count(*) FILTER (WHERE dropout_status = 'PENDING') AS still_today,
       min(start_day) AS first_day,
       max(start_day) AS last_day
FROM sm_sessions
GROUP BY arm, condition_met
ORDER BY arm, condition_met DESC;

-- ── 2. 처치 확인 ── 분석 대상(두 집단 · 조건 충족) 세션만.
SELECT arm,
       count(*) AS sessions,
       count(*) FILTER (WHERE explore_questions > 0) AS sessions_with_question,
       -- 첫 탐색 질문이 선택형이었던 비율(탐색 질문이 있는 세션 기준)
       round(100.0 * count(*) FILTER (WHERE first_question_as_choices)
             / NULLIF(count(*) FILTER (WHERE explore_questions > 0), 0), 1) AS first_choice_pct,
       -- 세션당 탐색 질문 수
       round(avg(explore_questions), 2) AS questions_per_session,
       -- 선택형 질문 비율(질문 단위)
       round(100.0 * sum(choice_questions) / NULLIF(sum(explore_questions), 0), 1) AS choice_question_pct,
       -- 재해석 방침이 한 번 이상 적용된 세션 비율
       round(100.0 * count(*) FILTER (WHERE reframe_turns > 0) / count(*), 1) AS reframe_session_pct
FROM sm_session_metrics
WHERE arm IN ('TREATMENT', 'CONTROL') AND condition_met
GROUP BY arm
ORDER BY arm DESC;

-- ── 3. 도출된 시작 성향 분포 ── 두 집단에서 비슷해야 한다(배정이 성향과 얽히지 않았는지).
SELECT arm,
       count(*) FILTER (WHERE derived_self_led) AS derived_self_led,
       count(*) FILTER (WHERE derived_other_view) AS derived_other_view,
       count(*) AS condition_met_sessions
FROM sm_sessions
WHERE arm IN ('TREATMENT', 'CONTROL') AND condition_met
GROUP BY arm
ORDER BY arm DESC;
