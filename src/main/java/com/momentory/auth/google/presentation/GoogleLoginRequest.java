package com.momentory.auth.google.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * ⚠ <b>nonce 칸이 없다</b> — 애플과 다른 점이다. FE 가 쓰는 구글 SDK 무료 모듈에
 * nonce 를 넘길 자리가 없어서, 받아도 대조할 수 없는 값이 된다
 * ({@code GoogleIdTokenVerifier} 머리말). 재생 방어는 {@code aud}·{@code azp} 가
 * 맡는다.
 */
public record GoogleLoginRequest(
        @Schema(description = "구글 로그인 SDK에서 발급한 ID Token(JWT)")
        @NotBlank(message = "idToken은 필수입니다.")
        String idToken
) {
}
