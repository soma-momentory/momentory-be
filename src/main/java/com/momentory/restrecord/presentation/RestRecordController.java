package com.momentory.restrecord.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.restrecord.application.RestRecordService;
import com.momentory.restrecord.domain.RestContent;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Rest Records", description = "쉼터 이용 기록 API")
@RestController
@RequestMapping("/api/v1/rest-records")
public class RestRecordController {

    private final RestRecordService restRecordService;

    public RestRecordController(RestRecordService restRecordService) {
        this.restRecordService = restRecordService;
    }

    @Operation(summary = "이용 기록 남기기", description = "쉼터 하나를 마치거나 중간에 나갈 때 기록을 남긴다. 끝까지 했으면 기분이 반드시 있고, 중간에 나갔으면 기분이 없다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "기록 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RestRecordResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
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
                            name = "contentRequired",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "쉼터를 입력해주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "completedRequired",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "끝까지 했는지 입력해주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "completedWithoutMood",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "끝까지 했을 때는 기분을 골라주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "incompleteWithMood",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "끝까지 하지 않았을 때는 기분을 남길 수 없습니다."
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
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RestRecordResponse addRecord(
            @Login LoginPrincipal principal,
            @Valid @RequestBody RestRecordRequest request
    ) {
        return RestRecordResponse.from(restRecordService.addRecord(principal.userId(), request.content(), request.completed(), request.mood()));
    }

    @Operation(summary = "이용 기록 조회", description = "그 쉼터의 이용 기록을 최신순으로 돌려준다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RestRecordListResponse.class))),
            @ApiResponse(responseCode = "400", description = "쉼터가 없거나 잘못됨", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "invalidContent",
                    value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "잘못된 요청입니다."
                            }
                            """
            ))),
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
    public RestRecordListResponse getRecords(
            @Login LoginPrincipal principal,
            @Parameter(description = "어떤 쉼터인지", example = "WORRY_BOX")
            @RequestParam("content") RestContent content
    ) {
        return RestRecordListResponse.from(restRecordService.getRecords(principal.userId(), content));
    }
}
