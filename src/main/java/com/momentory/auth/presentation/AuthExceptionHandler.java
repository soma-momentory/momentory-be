package com.momentory.auth.presentation;

import com.momentory.auth.kakao.infrastructure.KakaoApiErrorCode;
import com.momentory.auth.kakao.infrastructure.KakaoApiException;
import com.momentory.auth.kakao.presentation.KakaoLoginController;
import com.momentory.auth.logout.presentation.LogoutController;
import com.momentory.auth.token.application.RefreshTokenReissueException;
import com.momentory.auth.token.presentation.RefreshTokenReissueController;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.user.withdrawal.presentation.UserWithdrawalController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        KakaoLoginController.class,
        RefreshTokenReissueController.class,
        LogoutController.class,
        UserWithdrawalController.class
})
public class AuthExceptionHandler {

    @ExceptionHandler(KakaoApiException.class)
    ResponseEntity<ApiErrorResponse> handleKakaoApiException(KakaoApiException exception) {
        return switch (exception.getErrorCode()) {
            case INVALID_ACCESS_TOKEN -> error(HttpStatus.UNAUTHORIZED, "KAKAO_TOKEN_INVALID", "카카오 인증에 실패했습니다.");
            case APP_ID_MISMATCH -> error(HttpStatus.UNAUTHORIZED, "KAKAO_APP_ID_MISMATCH", "카카오 인증에 실패했습니다.");
            case USER_ID_MISMATCH -> error(HttpStatus.UNAUTHORIZED, "KAKAO_USER_ID_MISMATCH", "카카오 인증에 실패했습니다.");
            case EMAIL_CONSENT_REQUIRED -> error(HttpStatus.BAD_REQUEST, "KAKAO_EMAIL_CONSENT_REQUIRED", "카카오계정 이메일 제공 동의가 필요합니다.");
            case EMAIL_UNAVAILABLE -> error(HttpStatus.BAD_REQUEST, "KAKAO_EMAIL_UNAVAILABLE", "유효하고 인증된 카카오계정 이메일이 필요합니다.");
            case KAKAO_API_SERVER_ERROR -> error(HttpStatus.BAD_GATEWAY, "KAKAO_API_SERVER_ERROR", "카카오 서비스에 일시적인 오류가 발생했습니다.");
            case KAKAO_API_NETWORK_ERROR -> error(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_API_NETWORK_ERROR", "카카오 서비스에 연결할 수 없습니다.");
            case UNEXPECTED_KAKAO_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "KAKAO_API_RESPONSE_ERROR", "카카오 서비스 응답을 처리할 수 없습니다.");
        };
    }

    @ExceptionHandler(RefreshTokenReissueException.class)
    ResponseEntity<ApiErrorResponse> handleRefreshToken(RefreshTokenReissueException exception) {
        return switch (exception.getErrorCode()) {
            case INVALID -> error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "리프레시 토큰이 유효하지 않습니다.");
            case REVOKED -> error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", "리프레시 토큰이 이미 폐기되었습니다.");
            case EXPIRED -> error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "리프레시 토큰이 만료되었습니다.");
        };
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
