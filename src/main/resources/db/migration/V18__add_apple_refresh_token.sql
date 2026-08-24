-- 애플 연결 해제(revoke)에 쓸 refresh token 을 보관한다.
--
-- 애플은 탈퇴 시 서버가 /auth/revoke 를 부르기를 요구하는데(App Store 심사 규정),
-- 그때 넘길 토큰을 그 시점에 새로 얻을 방법이 없다. 사용자가 탈퇴 화면에서 다시
-- Sign in with Apple 을 하지는 않기 때문이다. 그래서 로그인 때 authorization code 를
-- 교환해 받은 refresh token 을 여기 눕혀 둔다.
--
-- nullable 이다. 애플 계정이 아닌 행(카카오)에는 없고, 이 변경 이전에 만들어진
-- 애플 계정에도 없다 — 그 사용자는 다음 로그인에서 채워진다.
ALTER TABLE oauth_accounts
    ADD COLUMN apple_refresh_token VARCHAR(512);
