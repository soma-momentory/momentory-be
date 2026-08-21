ALTER TABLE users
    ADD COLUMN email VARCHAR(320);

UPDATE users AS target
SET email = source.email
FROM (
    -- 과거 로그인에서 저장한 값을 보존한다. 이후 카카오 로그인 시 현재의
    -- 유효성·인증 여부를 검증한 이메일로 갱신한다.
    SELECT user_id, MAX(email) AS email
    FROM oauth_accounts
    WHERE email IS NOT NULL
    GROUP BY user_id
) AS source
WHERE target.id = source.user_id;

ALTER TABLE oauth_accounts
    DROP COLUMN email;
