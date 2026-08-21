package com.momentory.auth.kakao.infrastructure;

import com.momentory.auth.kakao.application.KakaoUserInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class KakaoApiClient {

    private static final String TOKEN_INFO_PATH = "/v1/user/access_token_info";
    private static final String USER_INFO_PATH = "/v2/user/me";

    private final RestClient restClient;
    private final KakaoApiProperties properties;

    public KakaoApiClient(
            @Qualifier("kakaoRestClient") RestClient restClient,
            KakaoApiProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public KakaoUserInfo getUserInfo(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.INVALID_ACCESS_TOKEN,
                    "Kakao access token is required."
            );
        }

        KakaoTokenInfoResponse tokenInfo = getTokenInfo(accessToken);
        Long tokenUserId = requireValue(tokenInfo == null ? null : tokenInfo.id(), "Kakao token user ID is missing.");
        Long appId = requireValue(tokenInfo == null ? null : tokenInfo.app_id(), "Kakao app ID is missing.");
        Long expiresIn = requireValue(tokenInfo == null ? null : tokenInfo.expires_in(), "Kakao token expiration is missing.");

        if (expiresIn < 0) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.INVALID_ACCESS_TOKEN,
                    "Kakao access token has expired."
            );
        }
        if (!properties.appId().equals(appId)) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.APP_ID_MISMATCH,
                    "Kakao access token belongs to a different application."
            );
        }

        KakaoUserResponse userResponse = getUserResponse(accessToken);
        Long userId = requireValue(userResponse == null ? null : userResponse.id(), "Kakao user ID is missing.");
        if (!tokenUserId.equals(userId)) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.USER_ID_MISMATCH,
                    "Kakao token and user IDs do not match."
            );
        }

        String email = requireVerifiedEmail(userResponse.kakao_account());
        return new KakaoUserInfo(userId.toString(), email);
    }

    private String requireVerifiedEmail(KakaoUserResponse.KakaoAccount account) {
        if (account != null && Boolean.TRUE.equals(account.email_needs_agreement())) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.EMAIL_CONSENT_REQUIRED,
                    "Kakao account email consent is required."
            );
        }
        String email = account == null ? null : account.email();
        if (account == null
                || !Boolean.TRUE.equals(account.is_email_valid())
                || !Boolean.TRUE.equals(account.is_email_verified())
                || email == null
                || email.isBlank()
                || email.length() > 320) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.EMAIL_UNAVAILABLE,
                    "A verified Kakao account email is required."
            );
        }
        return email.trim();
    }

    private KakaoTokenInfoResponse getTokenInfo(String accessToken) {
        return get(accessToken, TOKEN_INFO_PATH, KakaoTokenInfoResponse.class);
    }

    private KakaoUserResponse getUserResponse(String accessToken) {
        return get(accessToken, USER_INFO_PATH, KakaoUserResponse.class);
    }

    private <T> T get(String accessToken, String path, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            throw mapResponseException(exception);
        } catch (ResourceAccessException exception) {
            throw new KakaoApiException(
                    KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR,
                    "Kakao API could not be reached.",
                    exception
            );
        } catch (RestClientException exception) {
            if (isNetworkException(exception)) {
                throw new KakaoApiException(
                        KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR,
                        "Kakao API could not be reached.",
                        exception
                );
            }
            throw new KakaoApiException(
                    KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE,
                    "Kakao API returned an unexpected response.",
                    exception
            );
        }
    }

    private KakaoApiException mapResponseException(RestClientResponseException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        if (statusCode.is4xxClientError()) {
            return new KakaoApiException(
                    KakaoApiErrorCode.INVALID_ACCESS_TOKEN,
                    "Kakao access token is invalid or expired.",
                    exception
            );
        }
        if (statusCode.is5xxServerError()) {
            return new KakaoApiException(
                    KakaoApiErrorCode.KAKAO_API_SERVER_ERROR,
                    "Kakao API server failed.",
                    exception
            );
        }
        return new KakaoApiException(
                KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE,
                "Kakao API returned an unexpected response.",
                exception
        );
    }

    private <T> T requireValue(T value, String message) {
        if (value == null) {
            throw new KakaoApiException(KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE, message);
        }
        return value;
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
}
