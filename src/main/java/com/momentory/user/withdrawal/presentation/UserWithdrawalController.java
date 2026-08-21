package com.momentory.user.withdrawal.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.user.withdrawal.application.UserWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "사용자 API")
@RestController
@RequestMapping("/api/v1/users/me")
public class UserWithdrawalController {

    private final UserWithdrawalService userWithdrawalService;

    public UserWithdrawalController(UserWithdrawalService userWithdrawalService) {
        this.userWithdrawalService = userWithdrawalService;
    }

    @Operation(
            summary = "회원탈퇴",
            description = "현재 로그인 사용자의 카카오 연결을 해제하고 모멘토리 계정과 내부 데이터를 삭제합니다."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "회원탈퇴 성공"),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증이 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "AUTHENTICATION_REQUIRED",
                                    value = """
                                            {
                                              "code": "AUTHENTICATION_REQUIRED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "카카오 연결 해제 서버 또는 응답 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "KAKAO_API_SERVER_ERROR",
                                            value = """
                                                    {
                                                      "code": "KAKAO_API_SERVER_ERROR",
                                                      "message": "카카오 서비스에 일시적인 오류가 발생했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_API_RESPONSE_ERROR",
                                            value = """
                                                    {
                                                      "code": "KAKAO_API_RESPONSE_ERROR",
                                                      "message": "카카오 서비스 응답을 처리할 수 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "카카오 연결 해제 네트워크 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "KAKAO_API_NETWORK_ERROR",
                                    value = """
                                            {
                                              "code": "KAKAO_API_NETWORK_ERROR",
                                              "message": "카카오 서비스에 연결할 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@Login LoginPrincipal principal) {
        userWithdrawalService.withdraw(principal.userId());
    }
}
