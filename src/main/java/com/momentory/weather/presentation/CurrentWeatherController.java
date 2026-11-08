package com.momentory.weather.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.weather.application.CurrentWeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Weather", description = "현재 위치의 날씨")
@RestController
@RequestMapping("/api/v1/weather")
public class CurrentWeatherController {
    private final CurrentWeatherService service;

    public CurrentWeatherController(CurrentWeatherService service) {
        this.service = service;
    }

    @Operation(summary = "현재 위치의 날씨 조회", description = "좌표는 HTTPS 요청 본문으로만 받으며 저장하지 않는다. 조회는 상태를 바꾸지 않아 재시도할 수 있다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "날씨 조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CurrentWeatherResponse.class))),
            @ApiResponse(responseCode = "400", description = "좌표가 올바르지 않습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "INVALID_COORDINATE", value = "{\"code\":\"INVALID_COORDINATE\",\"message\":\"좌표가 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증이 필요합니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "429", description = "요청이 너무 많습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "TOO_MANY_REQUESTS", value = "{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"요청이 너무 많습니다.\"}"))),
            @ApiResponse(responseCode = "502", description = "날씨 제공자 응답이 올바르지 않습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "WEATHER_UPSTREAM_FAILED", value = "{\"code\":\"WEATHER_UPSTREAM_FAILED\",\"message\":\"날씨를 가져오지 못했어요.\"}"))),
            @ApiResponse(responseCode = "503", description = "날씨 기능을 사용할 수 없습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "WEATHER_UNAVAILABLE", value = "{\"code\":\"WEATHER_UNAVAILABLE\",\"message\":\"날씨 기능을 사용할 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "504", description = "날씨 제공자 응답 시간이 초과되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "WEATHER_UPSTREAM_TIMEOUT", value = "{\"code\":\"WEATHER_UPSTREAM_TIMEOUT\",\"message\":\"날씨를 가져오지 못했어요.\"}")))
    })
    @PostMapping(value = "/current", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CurrentWeatherResponse current(@Login LoginPrincipal principal,
            @Valid @RequestBody CurrentWeatherRequest request) {
        return CurrentWeatherResponse.from(service.current(principal.userId(),
                request.latitude().doubleValue(), request.longitude().doubleValue()));
    }
}
