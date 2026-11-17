-- 가설 전제 점검 — 「저항이 잦은 사용자는 응답 곤란도 많다」. 00_setup.sql 을 먼저 실행한다.
-- 문서 5장 「실험 전 점검」: 성향 반영을 끈 파일럿(집단 NONE · CONTROL)에서 사용자별 저항과 응답 곤란이 함께 움직이는지 본다.
-- 관련이 없으면 가설과 주 지표를 실험 전에 다시 검토한다.

-- ── 1. 사용자별 횟수 ──
DROP VIEW IF EXISTS sm_premise_users;
CREATE TEMP VIEW sm_premise_users AS
SELECT user_id,
       count(*) AS sessions,
       sum(explore_questions) AS questions,
       sum(resistance_questions) AS resistance,
       sum(struggle_questions) AS struggle,
       sum(resistance_questions)::float8 / NULLIF(sum(explore_questions), 0) AS resistance_rate,
       sum(struggle_questions)::float8 / NULLIF(sum(explore_questions), 0) AS struggle_rate
FROM sm_session_metrics
WHERE arm IN ('NONE', 'CONTROL')
GROUP BY user_id
HAVING sum(explore_questions) > 0;

SELECT * FROM sm_premise_users ORDER BY questions DESC;

-- ── 2. 상관 ── 횟수는 질문 수에 함께 끌려가므로 질문당 비율도 본다. 사용자가 적으면 순위 상관(스피어만)을 우선한다.
WITH ranked AS (
    SELECT u.*,
           rank() OVER (ORDER BY resistance_rate) AS r_resistance,
           rank() OVER (ORDER BY struggle_rate) AS r_struggle
    FROM sm_premise_users u
)
SELECT count(*) AS users,
       round(corr(resistance, struggle)::numeric, 3) AS pearson_counts,
       round(corr(resistance_rate, struggle_rate)::numeric, 3) AS pearson_rates,
       round(corr(r_resistance, r_struggle)::numeric, 3) AS spearman_rates
FROM ranked;
