-- 수집 전 · 수집 중 점검 — 지표에 쓸 기록이 빠짐없이 남는지 본다. 00_setup.sql 을 먼저 실행한다.
-- 「기대」 칸과 다르면 데이터를 더 모으기 전에 원인을 찾는다.

-- ── 1. 기록 누락 점검 ──
SELECT '전략 턴인데 question_sent 가 비어 있음' AS check_name,
       count(*) AS found, '0' AS expected
FROM retrospect_turn_judgments j
WHERE j.retrospect_id IN (SELECT retrospect_id FROM sm_sessions)
  AND j.policy_version IS NOT NULL AND j.question_sent IS NULL
UNION ALL
SELECT '끝난 회고(COMPLETED)인데 종료 사유가 없음', count(*), '0'
FROM sm_sessions WHERE status = 'COMPLETED' AND end_reason IS NULL
UNION ALL
SELECT '종료 사유는 있는데 종료 시각이 없음', count(*), '0'
FROM sm_sessions WHERE end_reason IS NOT NULL AND completed_at IS NULL
UNION ALL
SELECT '사용자 턴이 있는데 마지막 사용자 턴 시각이 없음', count(*), '0'
FROM sm_sessions WHERE logged_turns > 0 AND last_user_turn_at IS NULL
UNION ALL
SELECT '성향 관찰 줄이 없음(집단을 알 수 없음)', count(*), '0'
FROM sm_sessions WHERE NOT has_observation
UNION ALL
SELECT '일기 턴 번호가 비어 있음(되돌려진 턴 등)', count(*), '적을수록 좋음'
FROM sm_sessions WHERE logged_turns <> max_diary_turn
UNION ALL
SELECT '문장을 만든 전략 턴인데 새 이야기 칸이 비어 있음(V50 이후)', count(*), '0'
FROM retrospect_turn_judgments j
WHERE j.retrospect_id IN (SELECT retrospect_id FROM sm_sessions)
  AND j.policy_version IS NOT NULL AND j.strategy_type <> 'CLOSE' AND NOT j.generation_failed
  AND j.new_topic IS NULL
UNION ALL
SELECT '탐색 질문 뒤 새 이야기인데 응답 곤란으로 판단됨', count(*), '적을수록 좋음 (응답 곤란으로 셈)'
FROM sm_questions WHERE answer_new_topic IS TRUE AND response_class = 'STRUGGLE'
UNION ALL
SELECT '탐색 질문이 나갔는데 방침이 탐색 · 재해석이 아님', count(*), '0'
FROM sm_questions WHERE strategy_type NOT IN ('EXPLORE', 'REFRAME')
UNION ALL
SELECT '한 회고에 규칙 버전이 둘 이상', count(*), '0 (파일럿 중 규칙을 바꾸지 않는다)'
FROM sm_sessions WHERE policy_versions LIKE '%,%'
UNION ALL
SELECT '한 회고에 판단 질문 버전이 둘 이상', count(*), '0'
FROM sm_sessions WHERE questions_versions LIKE '%,%';

-- ── 2. 탐색 질문 뒤 반응의 분류 ── NO_FOLLOWUP · JUDGE_FAILED · LOW_CONFIDENCE 가 많으면 질문 단위 지표를 믿기 어렵다.
SELECT response_class,
       count(*) AS questions,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
FROM sm_questions
GROUP BY response_class
ORDER BY questions DESC;

-- ── 3. 회고 상태 분포 ── 종료 사유와 이탈 분류가 예상대로 갈리는지 본다.
SELECT dropout_status,
       COALESCE(end_reason, '(없음)') AS end_reason,
       status,
       count(*) AS sessions
FROM sm_sessions
GROUP BY dropout_status, end_reason, status
ORDER BY dropout_status, sessions DESC;

-- ── 4. 시스템 오류가 있었던 세션 ── 문서 5장: 시스템 오류는 이탈에서 빼고 따로 보고한다. 이탈로 분류된 것 중 오류가 낀 세션을 본다.
SELECT dropout_status,
       count(*) FILTER (WHERE had_generation_failure) AS with_generation_failure,
       count(*) FILTER (WHERE had_judge_failure) AS with_judge_failure,
       count(*) AS sessions
FROM sm_sessions
GROUP BY dropout_status
ORDER BY dropout_status;
