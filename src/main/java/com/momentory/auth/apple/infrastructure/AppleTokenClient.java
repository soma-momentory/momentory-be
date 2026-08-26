package com.momentory.auth.apple.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 애플 authorization code 를 <b>refresh token</b> 으로 바꾼다 —
 * {@code POST https://appleid.apple.com/auth/token}.
 *
 * <p><b>왜 로그인 때 하는가.</b> 탈퇴 시점에는 이 교환을 할 수 없다. code 는 인가
 * 한 번에 하나씩만 나오고 수명도 짧은데, 사용자가 탈퇴 화면에서 Sign in with Apple 을
 * 다시 하지는 않는다. 그래서 로그인에서 받아 두고 {@code oauth_accounts} 에 눕힌다.
 *
 * <p><b>실패해도 로그인을 막지 않는다.</b> 이 교환은 「나중에 탈퇴할 수 있게」 하는
 * 준비일 뿐이고, 지금 로그인하려는 사용자와는 무관하다 — 애플 토큰 서버가 잠시
 * 흔들린다고 로그인을 거절하면 잃는 것이 더 크다. 그래서 호출부가 실패를
 * {@code null} 로 받아 넘긴다({@link AppleLoginTransactionService}).
 */
@Component
public class AppleTokenClient {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenClient.class);
    private static final String GRANT_TYPE = "authorization_code";
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile(
            "\\\"error\\\"\\s*:\\s*\\\"([a-z_]+)\\\""
    );
    private static final Set<String> KNOWN_ERROR_CODES = Set.of(
            "invalid_request",
            "invalid_client",
            "invalid_grant",
            "unauthorized_client",
            "unsupported_grant_type",
            "invalid_scope"
    );

    private final RestClient restClient;
    private final AppleAuthProperties properties;
    private final AppleClientSecretGenerator clientSecretGenerator;

    public AppleTokenClient(
            AppleAuthProperties properties,
            AppleClientSecretGenerator clientSecretGenerator
    ) {
        this.properties = properties;
        this.clientSecretGenerator = clientSecretGenerator;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties))
                .build();
    }

    /**
     * @return 애플이 준 refresh token. 교환할 수 없거나 실패하면 {@code null} —
     *         <b>던지지 않는다</b>(위 머리말).
     */
    public String exchangeRefreshToken(String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            log.warn("애플 refresh token 교환을 건너뛴다. reason=authorization_code_missing");
            return null;
        }
        if (!properties.revokeConfigured()) {
            // .p8 이 아직 없다. 로그인은 그대로 되고, 이 사용자는 다음 로그인에서 채워진다
            log.error("애플 refresh token 교환을 건너뛴다. reason=revoke_credentials_missing");
            return null;
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", GRANT_TYPE);
            form.add("code", authorizationCode);
            form.add("client_id", properties.clientId());
            form.add("client_secret", clientSecretGenerator.generate());

            AppleTokenResponse response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(AppleTokenResponse.class);

            String refreshToken = response == null ? null : response.refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                log.warn("애플 refresh token 교환 응답에 토큰이 없다. reason=refresh_token_missing");
                return null;
            }
            return refreshToken;
        } catch (RestClientResponseException exception) {
            // 본문 전체를 남기지 않는다. Apple 표준 오류 코드만 허용 목록으로 걸러 기록한다.
            log.warn(
                    "애플 refresh token 교환을 거절당했다. reason=apple_rejected status={} apple_error={}",
                    exception.getStatusCode().value(),
                    appleErrorCode(exception)
            );
            return null;
        } catch (ResourceAccessException exception) {
            log.warn("애플 refresh token 교환에 실패했다. reason=network");
            return null;
        } catch (AppleApiException exception) {
            log.error(
                    "애플 refresh token 교환에 실패했다. reason=client_secret error_code={}",
                    exception.getErrorCode()
            );
            return null;
        } catch (RestClientException exception) {
            log.warn("애플 refresh token 교환 응답을 처리하지 못했다. reason=unexpected_response");
            return null;
        }
    }

    private String appleErrorCode(RestClientResponseException exception) {
        Matcher matcher = ERROR_CODE_PATTERN.matcher(exception.getResponseBodyAsString());
        if (!matcher.find()) {
            return "unknown";
        }
        String errorCode = matcher.group(1);
        return KNOWN_ERROR_CODES.contains(errorCode) ? errorCode : "unknown";
    }

    private static JdkClientHttpRequestFactory requestFactory(AppleAuthProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()
        );
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
