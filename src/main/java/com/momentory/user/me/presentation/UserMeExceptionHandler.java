package com.momentory.user.me.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.user.me.application.OnboardingRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserMeController.class)
public class UserMeExceptionHandler {

    @ExceptionHandler(OnboardingRequiredException.class)
    ResponseEntity<ApiErrorResponse> handleOnboardingRequired() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("ONBOARDING_REQUIRED", "온보딩을 먼저 마쳐 주세요."));
    }
}
