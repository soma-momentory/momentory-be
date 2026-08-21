package com.momentory.auth.kakao.infrastructure;

record KakaoUserResponse(
        Long id,
        KakaoAccount kakao_account
) {

    record KakaoAccount(
            Boolean email_needs_agreement,
            Boolean is_email_valid,
            Boolean is_email_verified,
            String email
    ) {
    }
}
