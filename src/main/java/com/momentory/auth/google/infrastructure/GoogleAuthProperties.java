package com.momentory.auth.google.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 구글 인증 설정. <b>전부 로그인에 필요하고 값이 있어야 한다</b> — 애플과 달리
 * 탈퇴용 설정이 따로 없다(구글은 서버가 끊을 연결을 들고 있지 않다 ·
 * {@code UserWithdrawalService}).
 *
 * <p>여기 있는 값은 전부 <b>공개 식별자</b>다. 클라이언트 ID 는 앱 바이너리에
 * 그대로 박히고(FE {@code .env} 의 {@code EXPO_PUBLIC_*}), JWKS 주소와 issuer 는
 * 구글이 문서로 공개한 상수다 — 그래서 {@code application.yml} 에 기본값을 둔다.
 * <b>비밀이 아니라서가 아니라, 없으면 부팅이 막히기 때문이다</b>: 애플
 * {@code client-id} 를 SSM 참조로 두었다가 CD 가 두 번 롤백된 적이 있다
 * ({@link com.momentory.auth.apple.infrastructure.AppleAuthProperties}).
 */
@Validated
@ConfigurationProperties(prefix = "google.auth")
public record GoogleAuthProperties(
        @NotBlank String jwkSetUri,

        /**
         * 받아들일 {@code iss}. <b>둘이다</b> — 구글은 같은 토큰을
         * {@code https://accounts.google.com} 과 {@code accounts.google.com} 중
         * 하나로 발급한다(스킴이 붙기도 붙지 않기도 한다). 하나만 받으면
         * <b>어느 날 갑자기 절반이 거절</b>되므로 애플처럼 단일 issuer 로 둘 수 없다.
         */
        @NotEmpty List<@NotBlank String> issuers,

        /**
         * 받아들일 {@code aud} — 우리 프로젝트의 클라이언트 ID 전부다.
         *
         * <p><b>플랫폼마다 다른 값이 온다.</b> Android 는 앱이 넘긴 <b>웹</b>
         * 클라이언트 ID 가, iOS 는 <b>iOS</b> 클라이언트 ID 가 {@code aud} 에 앉는다.
         * 애플처럼 값 하나로 못 잠그는 이유가 이것이고, 그래서 <b>목록</b>이다.
         *
         * <p>⚠ 이 목록에 <b>남의 프로젝트 클라이언트 ID 를 넣으면 안 된다.</b>
         * {@code aud} 는 「이 토큰은 누구에게 발급됐는가」이고, 그것이 곧 이 서버가
         * 인정하는 앱의 전부다.
         */
        @NotEmpty List<@NotBlank String> allowedAudiences,

        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
