package com.momentory.actioncard.presentation;

import java.time.DateTimeException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.actioncard.application.ActionCardNotFoundException;

@RestControllerAdvice(assignableTypes = ActionCardController.class)
public class ActionCardExceptionHandler {

    @ExceptionHandler(ActionCardNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleActionCardNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("ACTION_CARD_NOT_FOUND", "행동 카드를 찾을 수 없습니다."));
    }

    /** 월이 1~12 를 벗어나는 등 연·월 조합이 잘못됐을 때({@code YearMonth.of} 가 던진다). */
    @ExceptionHandler(DateTimeException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidYearMonth() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", "잘못된 연·월입니다."));
    }
}
