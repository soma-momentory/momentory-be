package com.momentory.auth.google.infrastructure;

import com.momentory.auth.google.application.GoogleUserInfo;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 구글이 발급한 ID token(JWT)을 구글 공개키(JWKS)로 검증한다.
 * 서명·만료는 Nimbus 디코더가, 발급자·대상({@code aud}·{@code azp})과 이메일
 * 신뢰성은 이 클래스가 확인한다.
 *
 * <p>애플의 {@code AppleIdentityTokenVerifier} 와 같은 자리이고 같은 모양이지만,
 * <b>두 군데가 다르다.</b> 아래 두 절이 그 이유다.
 *
 * <h2>nonce 가 없다 — 대신 {@code azp} 를 본다</h2>
 *
 * 애플 쪽은 nonce 원문과 토큰의 클레임을 맞춰 재생을 막는다. 구글은 그럴 수 없다:
 * FE 가 쓰는 {@code @react-native-google-signin/google-signin}(v16, 무료 모듈)에
 * <b>nonce 를 넘길 자리가 아예 없다</b>(nonce 는 유료 Universal 모듈에만 있다).
 * 없는 값을 요구하면 로그인이 통째로 막히므로 요구하지 않는다.
 *
 * <p>그 자리를 {@code azp}(authorized party)가 대신 메운다. 구글은 <b>토큰을
 * 실제로 요청한 클라이언트</b>를 이 클레임에 적는다 — Android 앱이 웹 클라이언트
 * ID 를 {@code serverClientId} 로 넘기면 {@code aud} 는 웹 클라이언트,
 * {@code azp} 는 그 <b>Android 클라이언트</b>가 된다. Android 클라이언트는
 * 패키지명 + 서명 지문으로 잠겨 있으므로, {@code azp} 까지 우리 것인지 보면
 * 「우리 프로젝트의, 우리 앱이 받아 온 토큰」까지 좁혀진다.
 *
 * <p>⚠ <b>nonce 만큼은 아니다.</b> 요청 하나를 통째로 가로챈 상대는 여전히 막지
 * 못하고, 그 창을 닫는 것은 HTTPS 다. 그래도 {@code aud} 만 보는 것보다는
 * 좁으므로, nonce 를 못 쓰는 동안 여기까지는 조인다.
 *
 * <h2>issuer 가 둘이다</h2>
 *
 * 구글은 {@code iss} 를 {@code https://accounts.google.com} 으로도
 * {@code accounts.google.com} 으로도 쓴다. 그래서 애플이 쓴
 * {@code JwtValidators.createDefaultWithIssuer}(단일 값)를 그대로 쓸 수 없고,
 * 목록을 보는 검사를 만들어 기본 검사 옆에 끼운다.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final String EMAIL_CLAIM = "email";
    private static final String EMAIL_VERIFIED_CLAIM = "email_verified";
    /** 토큰을 실제로 요청한 클라이언트. 구글이 넣어 주지만 <b>항상 있지는 않다</b> */
    private static final String AUTHORIZED_PARTY_CLAIM = "azp";
    private static final int MAX_EMAIL_LENGTH = 320;

    private final NimbusJwtDecoder jwtDecoder;
    private final GoogleAuthProperties properties;

    public GoogleIdTokenVerifier(GoogleAuthProperties properties) {
        this.properties = properties;
        this.jwtDecoder = createDecoder(properties);
    }

    /**
     * ID token 을 검증하고 사용자 정보를 꺼낸다.
     *
     * <p>순서가 규칙이다 — <b>서명이 먼저다.</b> 서명을 확인하기 전의 클레임은
     * 누구나 지어낼 수 있는 문자열이라, {@code aud} 든 {@code email} 이든 그것을
     * 먼저 읽는 것은 아무것도 검사하지 않은 것과 같다.
     */
    public GoogleUserInfo verify(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.INVALID_ID_TOKEN,
                    "Google ID token is required."
            );
        }

        Jwt jwt = decode(idToken);

        requireOurClient(jwt);

        String providerUserId = jwt.getSubject();
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.UNEXPECTED_GOOGLE_RESPONSE,
                    "Google user ID is missing."
            );
        }

        return new GoogleUserInfo(providerUserId, requireVerifiedEmail(jwt));
    }

    /**
     * 이 토큰이 <b>우리 프로젝트의 앱</b>에게 발급된 것인지 본다 (머리말 「nonce 가
     * 없다」).
     *
     * <p>{@code aud} 는 <b>반드시</b> 우리 클라이언트 중 하나여야 한다. {@code azp}
     * 는 <b>있을 때만</b> 본다 — 구글이 넣지 않는 경우가 있고, 없는 것을 거절로
     * 삼으면 정상 로그인이 막힌다. 다만 <b>있는데 남의 것이면 거절한다</b>:
     * 그건 우리 클라이언트 ID 를 {@code aud} 로 지정해 다른 앱이 받아 간 토큰이다.
     */
    private void requireOurClient(Jwt jwt) {
        boolean audienceIsOurs = jwt.getAudience().stream()
                .anyMatch(properties.allowedAudiences()::contains);
        if (!audienceIsOurs) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.CLIENT_ID_MISMATCH,
                    "Google ID token belongs to a different client."
            );
        }

        String authorizedParty = jwt.getClaimAsString(AUTHORIZED_PARTY_CLAIM);
        if (authorizedParty != null
                && !authorizedParty.isBlank()
                && !properties.allowedAudiences().contains(authorizedParty)) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.CLIENT_ID_MISMATCH,
                    "Google ID token was requested by a different client."
            );
        }
    }

    private Jwt decode(String idToken) {
        try {
            return jwtDecoder.decode(idToken);
        } catch (BadJwtException exception) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.INVALID_ID_TOKEN,
                    "Google ID token is invalid or expired.",
                    exception
            );
        } catch (JwtException exception) {
            throw mapKeyRetrievalException(exception);
        }
    }

    /**
     * 구글은 이메일을 <b>매번</b> 준다 — 애플처럼 「첫 인가 한 번」 문제가 없다.
     * 그래도 {@code email_verified} 를 보는 것은 같다: 확인되지 않은 주소로 계정을
     * 세우면 <b>남의 이메일을 자기 것이라 말한 계정</b>이 생긴다.
     */
    private String requireVerifiedEmail(Jwt jwt) {
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (!isEmailVerified(jwt)
                || email == null
                || email.isBlank()
                || email.length() > MAX_EMAIL_LENGTH) {
            throw new GoogleApiException(
                    GoogleApiErrorCode.EMAIL_UNAVAILABLE,
                    "A verified Google account email is required."
            );
        }
        return email.trim();
    }

    /** 구글도 애플처럼 boolean 또는 문자열("true")로 내려준다 */
    private boolean isEmailVerified(Jwt jwt) {
        Object emailVerified = jwt.getClaim(EMAIL_VERIFIED_CLAIM);
        if (emailVerified instanceof Boolean verified) {
            return verified;
        }
        return emailVerified instanceof String verified && Boolean.parseBoolean(verified);
    }

    private GoogleApiException mapKeyRetrievalException(JwtException exception) {
        if (isNetworkException(exception)) {
            return new GoogleApiException(
                    GoogleApiErrorCode.GOOGLE_API_NETWORK_ERROR,
                    "Google public keys could not be reached.",
                    exception
            );
        }
        if (isServerError(exception)) {
            return new GoogleApiException(
                    GoogleApiErrorCode.GOOGLE_API_SERVER_ERROR,
                    "Google public key server failed.",
                    exception
            );
        }
        return new GoogleApiException(
                GoogleApiErrorCode.UNEXPECTED_GOOGLE_RESPONSE,
                "Google public keys could not be processed.",
                exception
        );
    }

    private boolean isNetworkException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isServerError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().is5xxServerError()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static NimbusJwtDecoder createDecoder(GoogleAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        // ⚠ 기본 변환기는 iss 를 URL 로 바꾼다 — 스킴 없는 `accounts.google.com`
        // 은 URL 이 아니라서 그 자리에서 IllegalArgumentException 이 난다.
        // 검증기가 손도 대 보기 전에 터지므로, iss 만 문자열로 남긴다.
        decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                Map.of("iss", issuer -> issuer)
        ));
        // varargs 대신 List 오버로드를 쓴다 — 제네릭 배열 생성 경고를 만들지 않는다
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                List.of(issuerValidator(properties.issuers()))
        ));
        return decoder;
    }

    /**
     * {@code iss} 가 목록 안에 있는지 본다 (머리말 「issuer 가 둘이다」).
     *
     * <p>실패는 {@code JwtValidationException} 으로 나가고 그것은
     * {@code BadJwtException} 이라 {@link #decode} 가 {@code INVALID_ID_TOKEN} 으로
     * 접는다 — 애플에서 issuer 불일치가 도착하는 곳과 같다.
     */
    private static OAuth2TokenValidator<Jwt> issuerValidator(List<String> issuers) {
        return jwt -> {
            // getIssuer() 를 쓰지 않는다 — 그것은 URL 로 바꾸려다 위와 같은 이유로 터진다
            String issuer = jwt.getClaimAsString("iss");
            if (issuer != null && issuers.contains(issuer)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The iss claim is not valid",
                    "https://tools.ietf.org/html/rfc6750#section-3.1"
            ));
        };
    }
}
