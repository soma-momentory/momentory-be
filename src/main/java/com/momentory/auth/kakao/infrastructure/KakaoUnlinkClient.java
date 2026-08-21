package com.momentory.auth.kakao.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class KakaoUnlinkClient {

    private static final String UNLINK_PATH = "/v1/user/unlink";
    private static final int NOT_LINKED_ERROR_CODE = -101;

    private final RestClient restClient;
    private final KakaoApiProperties properties;
    private final ObjectMapper objectMapper;

    public KakaoUnlinkClient(
            @Qualifier("kakaoRestClient") RestClient restClient,
            KakaoApiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void unlink(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw unexpectedResponse("Kakao provider user ID is required.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", providerUserId);

        try {
            KakaoUnlinkResponse response = restClient.post()
                    .uri(UNLINK_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoUnlinkResponse.class);
            if (response == null
                    || response.id() == null
                    || !providerUserId.equals(response.id().toString())) {
                throw unexpectedResponse("Kakao unlink response user ID does not match.");
            }
        } catch (RestClientResponseException exception) {
            if (isAlreadyUnlinked(exception)) {
                return;
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw new KakaoApiException(
                        KakaoApiErrorCode.KAKAO_API_SERVER_ERROR,
                        "Kakao unlink API server failed.",
                        exception
                );
            }
            throw new KakaoApiException(
                    KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE,
                    "Kakao unlink API rejected the request.",
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw networkError(exception);
        } catch (RestClientException exception) {
            if (isNetworkException(exception)) {
                throw networkError(exception);
            }
            throw new KakaoApiException(
                    KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE,
                    "Kakao unlink API returned an unexpected response.",
                    exception
            );
        }
    }

    private boolean isAlreadyUnlinked(RestClientResponseException exception) {
        if (exception.getStatusCode() != HttpStatus.BAD_REQUEST) {
            return false;
        }
        try {
            JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
            return body.path("code").intValue() == NOT_LINKED_ERROR_CODE;
        } catch (RuntimeException parsingFailure) {
            return false;
        }
    }

    private KakaoApiException unexpectedResponse(String message) {
        return new KakaoApiException(KakaoApiErrorCode.UNEXPECTED_KAKAO_RESPONSE, message);
    }

    private KakaoApiException networkError(Throwable cause) {
        return new KakaoApiException(
                KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR,
                "Kakao unlink API could not be reached.",
                cause
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
}
