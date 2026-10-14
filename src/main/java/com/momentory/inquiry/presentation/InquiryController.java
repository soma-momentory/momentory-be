package com.momentory.inquiry.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.inquiry.application.InquiryService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inquiries", description = "문의하기 API")
@RestController
@RequestMapping("/api/v1/inquiries")
public class InquiryController {

    private final InquiryService inquiryService;

    public InquiryController(InquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @Operation(summary = "문의 보내기", description = "앱 안에서 쓴 문의를 남긴다. 답변은 메일로 하므로 받을 이메일은 선택이다. 기기 정보(운영체제 · 버전 · 앱 버전)는 앱이 채운다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "접수 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = InquiryResponse.class))),
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
                            name = "categoryRequired",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "문의 종류를 골라주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentRequiredOrBlank",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "문의 내용을 입력해주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentTooLong",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "문의 내용은 최대 2000자입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "invalidReplyEmail",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "이메일 형식이 올바르지 않습니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "deviceInfoTooLong",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "기기 정보는 최대 30자입니다."
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
    public InquiryResponse addInquiry(
            @Login LoginPrincipal principal,
            @Valid @RequestBody InquiryRequest request
    ) {
        return InquiryResponse.from(inquiryService.addInquiry(
                principal.userId(), request.category(), request.content(), request.replyEmail(),
                request.platform(), request.osVersion(), request.appVersion()));
    }
}
