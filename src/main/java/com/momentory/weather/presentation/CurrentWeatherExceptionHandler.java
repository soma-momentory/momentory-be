package com.momentory.weather.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.weather.application.WeatherDisabledException;
import com.momentory.weather.application.WeatherRateLimitException;
import com.momentory.weather.application.WeatherUpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CurrentWeatherController.class)
public class CurrentWeatherExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> invalidCoordinate() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_COORDINATE", "좌표가 올바르지 않습니다.");
    }

    @ExceptionHandler(WeatherRateLimitException.class)
    ResponseEntity<ApiErrorResponse> tooManyRequests() {
        return error(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "요청이 너무 많습니다.");
    }

    @ExceptionHandler(WeatherDisabledException.class)
    ResponseEntity<ApiErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "WEATHER_UNAVAILABLE", "날씨 기능을 사용할 수 없습니다.");
    }

    @ExceptionHandler(WeatherUpstreamException.class)
    ResponseEntity<ApiErrorResponse> upstream(WeatherUpstreamException exception) {
        boolean timeout = exception.reason() == WeatherUpstreamException.Reason.TIMEOUT;
        return error(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                timeout ? "WEATHER_UPSTREAM_TIMEOUT" : "WEATHER_UPSTREAM_FAILED", "날씨를 가져오지 못했어요.");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
