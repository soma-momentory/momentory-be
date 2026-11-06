package com.momentory.weeklyhappiness.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.weeklyhappiness.application.WeeklyHappinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WeeklyHappinessController.class)
public class WeeklyHappinessExceptionHandler {

    /** 주의 첫날이 아니거나, 아직 오지 않은 주이거나, 고칠 수 없는 지난 주 — 모두 요청을 고쳐야 하는 400 이다. */
    @ExceptionHandler(WeeklyHappinessException.class)
    ResponseEntity<ApiErrorResponse> handle(WeeklyHappinessException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(exception.code(), exception.getMessage()));
    }
}
