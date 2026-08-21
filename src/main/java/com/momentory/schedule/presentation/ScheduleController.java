package com.momentory.schedule.presentation;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.schedule.application.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Schedules", description = "홈 일정 API")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "날짜별 일정 조회")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "일정 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScheduleListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ScheduleListResponse getSchedules(
            @Login LoginPrincipal principal,
            @Parameter(example = "2026-08-10") @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ScheduleListResponse.from(scheduleService.getSchedules(principal.userId(), date));
    }

    @Operation(summary = "기간별 일정 조회")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "일정 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScheduleListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"),
                    @ExampleObject(name = "invalidPeriodRange", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"일정 조회 기간이 올바르지 않습니다.\"}")
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(value = "/period", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScheduleListResponse getSchedulesInPeriod(
            @Login LoginPrincipal principal,
            @Parameter(example = "2026-08-01") @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-08-31") @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ScheduleListResponse.from(scheduleService.getSchedulesInPeriod(principal.userId(), from, to));
    }

    @Operation(summary = "수동 일정 추가")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "일정 추가 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScheduleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"title은 필수입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScheduleResponse createSchedule(@Login LoginPrincipal principal, @Valid @RequestBody ScheduleRequest request) {
        return ScheduleResponse.from(scheduleService.createManualSchedule(principal.userId(), request.date(), request.title()));
    }

    @Operation(summary = "기기 캘린더 일정 동기화")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캘린더 동기화 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CalendarSyncResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "validationError", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"externalId는 필수입니다.\"}"),
                    @ExampleObject(name = "invalidSyncRange", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"캘린더 동기화 요청이 올바르지 않습니다.\"}")
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CalendarSyncResponse syncCalendar(
            @Login LoginPrincipal principal,
            @Valid @RequestBody CalendarSyncRequest request
    ) {
        return CalendarSyncResponse.from(scheduleService.syncCalendar(
                principal.userId(), request.from(), request.to(), request.toEvents()
        ));
    }

    @Operation(summary = "수동 일정 수정")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "일정 수정 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScheduleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"title은 필수입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일정을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = "{\"code\":\"SCHEDULE_NOT_FOUND\",\"message\":\"일정을 찾을 수 없습니다.\"}")))
    })
    @PatchMapping(value = "/{scheduleId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScheduleResponse updateSchedule(
            @Login LoginPrincipal principal,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleRequest request
    ) {
        return ScheduleResponse.from(scheduleService.updateManualSchedule(principal.userId(), scheduleId, request.date(), request.title()));
    }

    @Operation(summary = "캘린더 일정 숨김 상태 변경")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "숨김 상태 변경 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "validationError", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"hidden은 필수입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일정을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = "{\"code\":\"SCHEDULE_NOT_FOUND\",\"message\":\"일정을 찾을 수 없습니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PatchMapping(value = "/{scheduleId}/visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void changeCalendarVisibility(
            @Login LoginPrincipal principal,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleVisibilityRequest request
    ) {
        scheduleService.changeCalendarVisibility(principal.userId(), scheduleId, request.hidden());
    }

    @Operation(summary = "일정 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "일정 삭제 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일정을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = "{\"code\":\"SCHEDULE_NOT_FOUND\",\"message\":\"일정을 찾을 수 없습니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{scheduleId}")
    public void deleteSchedule(@Login LoginPrincipal principal, @PathVariable Long scheduleId) {
        scheduleService.deleteSchedule(principal.userId(), scheduleId);
    }

    @Operation(summary = "일정 완료 및 감정 변경")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "완료 상태 변경 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScheduleCompletionResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "잘못된 요청입니다."
                            }
                            """),
                    @ExampleObject(name = "incompleteScheduleWithEmotion", value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "미완료 상태에서는 감정을 선택할 수 없습니다."
                            }
                            """)
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = """
                    {
                      "code": "AUTHENTICATION_REQUIRED",
                      "message": "인증이 필요합니다."
                    }
                    """))),
            @ApiResponse(responseCode = "404", description = "일정을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = """
                    {
                      "code": "SCHEDULE_NOT_FOUND",
                      "message": "일정을 찾을 수 없습니다."
                    }
                    """)))
    })
    @PutMapping(value = "/{scheduleId}/completion", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScheduleCompletionResponse changeCompletion(
            @Login LoginPrincipal principal,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleCompletionRequest request
    ) {
        return ScheduleCompletionResponse.from(scheduleService.changeCompletion(
                principal.userId(), scheduleId, request.completed(), request.emotion()
        ));
    }

    @Operation(summary = "같은 날짜의 일정 순서 변경")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "일정 순서 변경 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "잘못된 요청입니다."
                            }
                            """),
                    @ExampleObject(name = "invalidScheduleOrder", value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "일정 순서 변경 요청이 올바르지 않습니다."
                            }
                            """)
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = """
                    {
                      "code": "AUTHENTICATION_REQUIRED",
                      "message": "인증이 필요합니다."
                    }
                    """))),
            @ApiResponse(responseCode = "404", description = "일정을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = """
                    {
                      "code": "SCHEDULE_NOT_FOUND",
                      "message": "일정을 찾을 수 없습니다."
                    }
                    """)))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PatchMapping(value = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void changeScheduleOrder(@Login LoginPrincipal principal, @Valid @RequestBody ScheduleOrderRequest request) {
        scheduleService.changeScheduleOrder(principal.userId(), request.date(), request.scheduleIds());
    }
}
