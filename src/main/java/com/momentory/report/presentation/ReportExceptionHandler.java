package com.momentory.report.presentation;

import java.time.DateTimeException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.momentory.common.presentation.ApiErrorResponse;

@RestControllerAdvice(assignableTypes = WeeklyReportController.class)
public class ReportExceptionHandler {

    /**
     * 주 경계를 잡다 날짜 범위를 넘어설 때 — {@code date} 가 {@code LocalDate} 의 끝자락이면 그 주의
     * 일요일이 표현 범위를 벗어난다. 형식이 틀린 {@code date} 와 같은 400 으로 돌려준다.
     */
    @ExceptionHandler(DateTimeException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidDate() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", "잘못된 요청입니다."));
    }
}
