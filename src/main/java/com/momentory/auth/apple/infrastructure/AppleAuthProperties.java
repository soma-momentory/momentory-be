package com.momentory.auth.apple.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 애플 인증 설정.
 *
 * <p>앞의 다섯은 <b>로그인</b>에 필요하고 전부 값이 있어야 한다. 뒤의 셋은
 * <b>탈퇴 시 연결 해제(revoke)</b>에만 쓰이고 <b>없어도 앱이 뜬다.</b>
 *
 * <p>⚠ 뒤의 셋을 필수로 두지 않는 것은 의도다. 발급 전인 값을 필수로 걸면 배포가
 * 통째로 막힌다 — {@code APPLE_CLIENT_ID} 를 SSM 참조로 두었다가 CD 가 두 번
 * 롤백된 적이 있다. 설정이 없으면 <b>로그인은 되고 탈퇴만 막힌다</b>(그쪽이
 * 조용히 성공했다고 말하는 것보다 낫다 · {@link AppleRevokeClient}).
 */
@Validated
@ConfigurationProperties(prefix = "apple.auth")
public record AppleAuthProperties(
        @NotBlank String jwkSetUri,
        @NotBlank String issuer,
        @NotBlank String clientId,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,

        /** 애플 개발자 팀 ID. 공개 식별자다 */
        String teamId,
        /** Sign in with Apple 키의 Key ID. 공개 식별자다 */
        String keyId,
        /** ⚠ <b>비밀.</b> {@code .p8} 의 내용(PKCS#8 PEM). 로그에 남기지 않는다 */
        String privateKey,

        @NotBlank String tokenUri,
        @NotBlank String revokeUri
) {

    /**
     * 연결 해제를 할 수 있는 상태인가 — 셋이 모두 있어야 한다.
     *
     * <p>부분 설정은 <b>없는 것으로 본다.</b> 하나라도 비면 client secret 을 서명할 수
     * 없고, 그 상태로 애플을 부르면 사유를 알기 어려운 실패가 된다.
     */
    public boolean revokeConfigured() {
        return hasText(teamId) && hasText(keyId) && hasText(privateKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
