package com.momentory.notification.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.notification.application.NotificationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationException.class)
    ResponseEntity<ApiErrorResponse> handle(NotificationException exception) {
        HttpStatus status = exception.code().equals("NOTIFICATION_NOT_FOUND")
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ApiErrorResponse(exception.code(), exception.getMessage()));
    }
}
