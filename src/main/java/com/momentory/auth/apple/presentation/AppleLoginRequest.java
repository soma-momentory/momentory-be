package com.momentory.auth.apple.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AppleLoginRequest(
        @Schema(description = "React Native 애플 로그인 SDK에서 발급한 Identity Token(JWT)")
        @NotBlank(message = "identityToken은 필수입니다.")
        String identityToken,

        @Schema(description = "재생 공격 방지용 nonce 원문. 앱은 이 값의 SHA-256 hex 를 애플에 전달하고, 서버가 identity token 의 nonce 클레임과 대조합니다.")
        @NotBlank(message = "nonce는 필수입니다.")
        String nonce,

        @Schema(description = "애플 authorization code. 탈퇴 시 연결 해제(revoke)에 쓸 refresh token 으로 교환해 보관합니다. 없어도 로그인은 됩니다.")
        String authorizationCode
) {
}
