package com.momentory.weeklyhappiness.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.weeklyhappiness.application.WeeklyHappinessService;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Weekly Happiness", description = "쉼터 주간 행복 API")
@RestController
@RequestMapping("/api/v1/weekly-happiness")
public class WeeklyHappinessController {

    private final WeeklyHappinessService weeklyHappinessService;

    public WeeklyHappinessController(WeeklyHappinessService weeklyHappinessService) {
        this.weeklyHappinessService = weeklyHappinessService;
    }

    @Operation(summary = "한 주의 주간 행복 조회",
            description = "그 주(일~토 · 일요일 04:00 KST 에 바뀐다)에 적은 칸을 칸 순서대로 돌려준다. 적은 칸이 없으면 빈 배열이다. "
                    + "firstWeekStart 는 적은 칸이 하나라도 있는 가장 이른 주다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WeeklyHappinessResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 주", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(
                            name = "invalidDateFormat",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "잘못된 요청입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "WEEKLY_HAPPINESS_INVALID_WEEK_START",
                            value = """
                                    {
                                      "code": "WEEKLY_HAPPINESS_INVALID_WEEK_START",
                                      "message": "주의 첫날(일요일) 날짜를 보내주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "WEEKLY_HAPPINESS_FUTURE_WEEK",
                            value = """
                                    {
                                      "code": "WEEKLY_HAPPINESS_FUTURE_WEEK",
                                      "message": "아직 오지 않은 주입니다."
                                    }
                                    """
                    )
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """
            )))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public WeeklyHappinessResponse getWeek(
            @Login LoginPrincipal principal,
            @Parameter(description = "그 주의 일요일", example = "2026-11-01", required = true)
            @RequestParam("weekStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart
    ) {
        return WeeklyHappinessResponse.from(weeklyHappinessService.getWeek(principal.userId(), weekStart));
    }

    @Operation(summary = "이번 주 주간 행복 저장",
            description = "이번 주(일요일 04:00 KST 경계)의 기록장을 통째로 바꾼다. 보낸 칸은 적거나 고치고, 보내지 않았거나 비운(공백뿐인) "
                    + "칸은 지운다. 같은 요청을 다시 보내도 결과가 같다. 쓰는 것은 이번 주뿐이다 — 지난 주는 읽기만 하고, 아직 오지 않은 주도 안 된다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공 — 저장한 뒤의 기록장", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WeeklyHappinessResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(
                            name = "invalidDateFormat",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "잘못된 요청입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "malformedRequestBody",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "잘못된 요청입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "entriesRequired",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "적은 칸 목록을 보내주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "categoryRequired",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "칸을 골라주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "duplicateCategory",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "같은 칸을 두 번 보낼 수 없습니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentTooLong",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "주간 행복 한 칸은 최대 100자입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "WEEKLY_HAPPINESS_INVALID_WEEK_START",
                            value = """
                                    {
                                      "code": "WEEKLY_HAPPINESS_INVALID_WEEK_START",
                                      "message": "주의 첫날(일요일) 날짜를 보내주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "WEEKLY_HAPPINESS_FUTURE_WEEK",
                            value = """
                                    {
                                      "code": "WEEKLY_HAPPINESS_FUTURE_WEEK",
                                      "message": "아직 오지 않은 주입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "WEEKLY_HAPPINESS_PAST_WEEK",
                            value = """
                                    {
                                      "code": "WEEKLY_HAPPINESS_PAST_WEEK",
                                      "message": "지난 주는 고칠 수 없습니다."
                                    }
                                    """
                    )
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """
            )))
    })
    @PutMapping(value = "/{weekStart}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public WeeklyHappinessResponse replaceWeek(
            @Login LoginPrincipal principal,
            @Parameter(description = "이번 주의 일요일", example = "2026-11-01")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @Valid @RequestBody WeeklyHappinessRequest request
    ) {
        return WeeklyHappinessResponse.from(
                weeklyHappinessService.replaceWeek(principal.userId(), weekStart, request.entriesByCategory()));
    }
}
