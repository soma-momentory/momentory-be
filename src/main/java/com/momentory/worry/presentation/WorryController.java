package com.momentory.worry.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.worry.application.WorryService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Worries", description = "쉼터 걱정상자 — 맡겨둔 걱정 API")
@RestController
@RequestMapping("/api/v1/worries")
public class WorryController {

    private final WorryService worryService;

    public WorryController(WorryService worryService) {
        this.worryService = worryService;
    }

    @Operation(summary = "걱정 맡겨두기", description = "「잠시 맡겨둘게요」를 고른 걱정 한 줄을 지금이 속한 하루(KST · 04:00 경계)에 남긴다. 「흘려보낼게요」를 고른 걱정은 보내지 않는다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "맡겨두기 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorryResponse.class))),
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
                            name = "contentRequiredOrBlank",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "걱정 내용을 입력해주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentTooLong",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "걱정 내용은 최대 100자입니다."
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
    public WorryResponse keepWorry(
            @Login LoginPrincipal principal,
            @Valid @RequestBody WorryRequest request
    ) {
        return WorryResponse.from(worryService.keepWorry(principal.userId(), request.content()));
    }

    @Operation(summary = "맡겨둔 걱정 목록 조회", description = "맡겨둔 걱정 전부를 최신순으로 돌려준다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorryListResponse.class))),
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
    public WorryListResponse getWorries(@Login LoginPrincipal principal) {
        return WorryListResponse.from(worryService.getWorries(principal.userId()));
    }
}
