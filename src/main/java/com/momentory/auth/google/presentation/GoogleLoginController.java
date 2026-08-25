package com.momentory.auth.google.presentation;

import com.momentory.auth.google.application.GoogleLoginResult;
import com.momentory.auth.google.application.GoogleLoginService;
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
public class GoogleLoginController {

    private final GoogleLoginService googleLoginService;

    public GoogleLoginController(GoogleLoginService googleLoginService) {
        this.googleLoginService = googleLoginService;
    }

    @Operation(
            summary = "구글 Native 로그인",
            description = "React Native 구글 로그인 SDK ID Token을 검증하고 모멘토리 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GoogleLoginResponse.class),
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
                                                      "message": "idToken은 필수입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "GOOGLE_EMAIL_UNAVAILABLE",
                                            value = """
                                                    {
                                                      "code": "GOOGLE_EMAIL_UNAVAILABLE",
                                                      "message": "유효하고 인증된 구글계정 이메일이 필요합니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "구글 ID Token 검증 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "GOOGLE_TOKEN_INVALID",
                                            value = """
                                                    {
                                                      "code": "GOOGLE_TOKEN_INVALID",
                                                      "message": "구글 인증에 실패했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "GOOGLE_CLIENT_ID_MISMATCH",
                                            value = """
                                                    {
                                                      "code": "GOOGLE_CLIENT_ID_MISMATCH",
                                                      "message": "구글 인증에 실패했습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "구글 인증 서버 또는 응답 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "GOOGLE_API_SERVER_ERROR",
                                            value = """
                                                    {
                                                      "code": "GOOGLE_API_SERVER_ERROR",
                                                      "message": "구글 서비스에 일시적인 오류가 발생했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "GOOGLE_API_RESPONSE_ERROR",
                                            value = """
                                                    {
                                                      "code": "GOOGLE_API_RESPONSE_ERROR",
                                                      "message": "구글 서비스 응답을 처리할 수 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "구글 인증 서버 네트워크 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "GOOGLE_API_NETWORK_ERROR",
                                    value = """
                                            {
                                              "code": "GOOGLE_API_NETWORK_ERROR",
                                              "message": "구글 서비스에 연결할 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping(value = "/google", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public GoogleLoginResponse login(@Valid @RequestBody GoogleLoginRequest request) {
        GoogleLoginResult result = googleLoginService.login(request.idToken());
        return GoogleLoginResponse.from(result);
    }
}
