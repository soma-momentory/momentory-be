package com.momentory.auth.apple.presentation;

import com.momentory.auth.apple.application.AppleLoginResult;
import com.momentory.auth.apple.application.AppleLoginService;
import com.momentory.common.presentation.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
public class AppleLoginController {

    private final AppleLoginService appleLoginService;

    public AppleLoginController(AppleLoginService appleLoginService) {
        this.appleLoginService = appleLoginService;
    }

    @Operation(
            summary = "애플 Native 로그인",
            description = "React Native 애플 로그인 SDK Identity Token을 검증하고 모멘토리 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AppleLoginResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "accessToken": "발급된 모멘토리 Access Token",
                                      "refreshToken": "발급된 모멘토리 Refresh Token",
                                      "tokenType": "Bearer",
                                      "accessTokenExpiresIn": 1800,
                                      "userId": 1,
                                      "onboardingRequired": true
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "validationError",
                                            value = """
                                                    {
                                                      "code": "INVALID_REQUEST",
                                                      "message": "identityToken은 필수입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "unreadableRequest",
                                            value = """
                                                    {
                                                      "code": "INVALID_REQUEST",
                                                      "message": "잘못된 요청입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "APPLE_EMAIL_UNAVAILABLE",
                                            value = """
                                                    {
                                                      "code": "APPLE_EMAIL_UNAVAILABLE",
                                                      "message": "유효하고 인증된 애플계정 이메일이 필요합니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "애플 Identity Token 검증 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "APPLE_TOKEN_INVALID",
                                            value = """
                                                    {
                                                      "code": "APPLE_TOKEN_INVALID",
                                                      "message": "애플 인증에 실패했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "APPLE_NONCE_MISMATCH",
                                            value = """
                                                    {
                                                      "code": "APPLE_NONCE_MISMATCH",
                                                      "message": "애플 인증에 실패했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "APPLE_CLIENT_ID_MISMATCH",
                                            value = """
                                                    {
                                                      "code": "APPLE_CLIENT_ID_MISMATCH",
                                                      "message": "애플 인증에 실패했습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "애플 인증 서버 또는 응답 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "APPLE_API_SERVER_ERROR",
                                            value = """
                                                    {
                                                      "code": "APPLE_API_SERVER_ERROR",
                                                      "message": "애플 서비스에 일시적인 오류가 발생했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "APPLE_API_RESPONSE_ERROR",
                                            value = """
                                                    {
                                                      "code": "APPLE_API_RESPONSE_ERROR",
                                                      "message": "애플 서비스 응답을 처리할 수 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "애플 인증 서버 네트워크 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "APPLE_API_NETWORK_ERROR",
                                    value = """
                                            {
                                              "code": "APPLE_API_NETWORK_ERROR",
                                              "message": "애플 서비스에 연결할 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping(value = "/apple", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AppleLoginResponse login(@Valid @RequestBody AppleLoginRequest request) {
        AppleLoginResult result = appleLoginService.login(
                request.identityToken(), request.nonce(), request.authorizationCode());
        return AppleLoginResponse.from(result);
    }
}
