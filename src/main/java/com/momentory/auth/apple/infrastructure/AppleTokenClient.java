package com.momentory.auth.apple.infrastructure;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;

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

    private static final String GRANT_TYPE = "authorization_code";

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
            return null;
        }
        if (!properties.revokeConfigured()) {
            // .p8 이 아직 없다. 로그인은 그대로 되고, 이 사용자는 다음 로그인에서 채워진다
            return null;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("code", authorizationCode);
        form.add("client_id", properties.clientId());
        form.add("client_secret", clientSecretGenerator.generate());

        try {
            AppleTokenResponse response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(AppleTokenResponse.class);

            return response == null ? null : response.refreshToken();
        } catch (RestClientException | AppleApiException exception) {
            // 본문을 남기지 않는다 — 여기 오는 응답에는 토큰이 실려 있을 수 있다
            return null;
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(AppleAuthProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()
        );
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
