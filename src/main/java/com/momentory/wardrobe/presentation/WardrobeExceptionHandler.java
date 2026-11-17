package com.momentory.wardrobe.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.wardrobe.application.WardrobeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WardrobeController.class)
public class WardrobeExceptionHandler {

    @ExceptionHandler(WardrobeException.class)
    ResponseEntity<ApiErrorResponse> handle(WardrobeException exception) {
        HttpStatus status = switch (exception.code()) {
            case "WARDROBE_ITEM_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "WARDROBE_ITEM_NOT_OWNED" -> HttpStatus.FORBIDDEN;
            case "TANGERINE_NOT_ENOUGH" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiErrorResponse(exception.code(), exception.getMessage()));
    }
}
