package com.momentory.wish.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.wish.application.WishException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WishController.class)
public class WishExceptionHandler {

    @ExceptionHandler(WishException.class)
    ResponseEntity<ApiErrorResponse> handle(WishException exception) {
        HttpStatus status = switch (exception.code()) {
            case "WISH_RULES_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "WISH_DAILY_LIMIT" -> HttpStatus.CONFLICT;
            case "WISH_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "WISH_REVIEW_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiErrorResponse(exception.code(), exception.getMessage()));
    }
}
