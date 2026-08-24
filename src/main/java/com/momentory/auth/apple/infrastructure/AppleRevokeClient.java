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

/**
 * 탈퇴할 때 애플 연결을 끊는다 — {@code POST https://appleid.apple.com/auth/revoke}.
 *
 * <p>App Store 심사 규정이 요구한다: Sign in with Apple 로 가입한 사용자가 계정을
 * 지우면 <b>서버가 애플에도 알려야</b> 사용자의 Apple ID 설정에서 우리 앱이 사라진다.
 * 지우기만 하고 알리지 않으면 사용자 쪽에는 연결이 남는다.
 *
 * <p>카카오 unlink({@code KakaoUnlinkClient})와 같은 자리이고 같은 규약을 따른다 —
 * <b>실패하면 던지고, 그래서 탈퇴가 실패한다.</b> 연결을 끊지 못했는데 계정만
 * 지우면 되돌릴 방법이 없다(계정이 사라진 뒤에는 어느 토큰으로 끊어야 하는지도 잃는다).
 *
 * <h2>설정이 없을 때는 지나간다 — 그리고 시끄럽게 남긴다</h2>
 *
 * {@code .p8} 이 없으면 <b>경고를 남기고 탈퇴를 계속</b>한다. 막지 않는 이유는,
 * 계정 삭제 자체도 심사 규정이 요구하는 기능이기 때문이다 — 연결을 못 끊는 것보다
 * <b>탈퇴가 통째로 막히는 쪽이 더 나쁘다.</b>
 *
 * <p>⚠ 그래서 <b>운영 배포 전에 반드시 설정돼야 한다.</b> 설정 없이 나가면 애플
 * 사용자의 연결이 조용히 남고, 그 사실은 이 경고 로그로만 드러난다.
 */
@Component
public class AppleRevokeClient {

    private static final Logger log = LoggerFactory.getLogger(AppleRevokeClient.class);
    private static final String TOKEN_TYPE_HINT = "refresh_token";

    private final RestClient restClient;
    private final AppleAuthProperties properties;
    private final AppleClientSecretGenerator clientSecretGenerator;

    public AppleRevokeClient(
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
     * @param appleRefreshToken 로그인 때 받아 둔 값. 없을 수 있다 — 이 기능이 붙기 전에
     *                          가입한 사용자이거나, 그 사이 교환이 실패한 경우다
     */
    public void revoke(String appleRefreshToken) {
        if (!properties.revokeConfigured()) {
            log.warn("애플 연결 해제를 건너뛴다 — .p8 설정이 없다. 운영에서는 반드시 설정해야 한다.");
            return;
        }
        if (appleRefreshToken == null || appleRefreshToken.isBlank()) {
            // 끊을 토큰이 없다. 막아 봐야 사용자가 할 수 있는 일이 없어 탈퇴는 계속한다
            log.warn("애플 연결 해제를 건너뛴다 — 보관된 refresh token 이 없다.");
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", appleRefreshToken);
        form.add("token_type_hint", TOKEN_TYPE_HINT);
        form.add("client_id", properties.clientId());
        form.add("client_secret", clientSecretGenerator.generate());

        try {
            restClient.post()
                    .uri(properties.revokeUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new AppleApiException(
                    exception.getStatusCode().is5xxServerError()
                            ? AppleApiErrorCode.APPLE_API_SERVER_ERROR
                            : AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE,
                    "Apple rejected the revoke request.",
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw new AppleApiException(
                    AppleApiErrorCode.APPLE_API_NETWORK_ERROR,
                    "Apple revoke endpoint could not be reached.",
                    exception
            );
        } catch (RestClientException exception) {
            throw new AppleApiException(
                    AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE,
                    "Apple revoke response could not be processed.",
                    exception
            );
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
