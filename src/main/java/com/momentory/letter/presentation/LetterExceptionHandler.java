package com.momentory.letter.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.letter.application.LetterActionLockedException;
import com.momentory.letter.application.LetterActionNotFoundException;
import com.momentory.letter.application.LetterNotFoundException;
import com.momentory.letter.application.LetterScheduleNotFoundException;

@RestControllerAdvice(assignableTypes = LetterController.class)
public class LetterExceptionHandler {

    static final String LETTER_NOT_FOUND_MESSAGE = "편지를 찾을 수 없습니다.";
    static final String LETTER_ACTION_NOT_FOUND_MESSAGE = "편지에 그 작은 일이 없습니다.";
    static final String SCHEDULE_NOT_FOUND_MESSAGE = "일정을 찾을 수 없습니다.";
    static final String LETTER_ACTION_LOCKED_MESSAGE = "이미 해 본 작은 일은 바꿀 수 없습니다.";

    @ExceptionHandler(LetterNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleLetterNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("LETTER_NOT_FOUND", LETTER_NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(LetterActionNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleActionNotFound() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("LETTER_ACTION_NOT_FOUND", LETTER_ACTION_NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(LetterScheduleNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleScheduleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("SCHEDULE_NOT_FOUND", SCHEDULE_NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(LetterActionLockedException.class)
    ResponseEntity<ApiErrorResponse> handleActionLocked() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("LETTER_ACTION_LOCKED", LETTER_ACTION_LOCKED_MESSAGE));
    }
}
