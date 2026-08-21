package com.momentory.schedule.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.schedule.application.ScheduleNotFoundException;
import com.momentory.schedule.application.InvalidScheduleOrderException;
import com.momentory.schedule.application.InvalidSchedulePeriodException;
import com.momentory.schedule.application.InvalidCalendarSyncException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ScheduleController.class)
public class ScheduleExceptionHandler {

    @ExceptionHandler(ScheduleNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleScheduleNotFound() {
        return scheduleError(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");
    }

    @ExceptionHandler(InvalidScheduleOrderException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidScheduleOrder() {
        return scheduleError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "일정 순서 변경 요청이 올바르지 않습니다.");
    }

    @ExceptionHandler(InvalidCalendarSyncException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidCalendarSync() {
        return scheduleError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "캘린더 동기화 요청이 올바르지 않습니다.");
    }

    @ExceptionHandler(InvalidSchedulePeriodException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidSchedulePeriod() {
        return scheduleError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "일정 조회 기간이 올바르지 않습니다.");
    }

    private ResponseEntity<ApiErrorResponse> scheduleError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
