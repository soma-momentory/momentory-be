package com.momentory.auth.apple.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /auth/token} 의 응답 중 <b>우리가 쓰는 한 칸</b>.
 *
 * <p>애플은 {@code access_token}·{@code id_token}·{@code expires_in} 도 함께 주지만
 * 받아 두지 않는다 — 연결 해제에 필요한 것은 refresh token 뿐이고, 쓰지 않을 비밀을
 * 들고 있을 이유가 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppleTokenResponse(@JsonProperty("refresh_token") String refreshToken) {
}
