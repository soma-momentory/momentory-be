package com.momentory.retrospect.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.retrospect.application.RetrospectSessionNotFoundException;

@RestControllerAdvice(assignableTypes = RetrospectController.class)
public class RetrospectExceptionHandler {

    @ExceptionHandler(RetrospectSessionNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleSessionNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("RETROSPECT_SESSION_NOT_FOUND", "회고 세션을 찾을 수 없습니다."));
    }

    @ExceptionHandler(InvalidStartException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidStart(InvalidStartException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }
}
