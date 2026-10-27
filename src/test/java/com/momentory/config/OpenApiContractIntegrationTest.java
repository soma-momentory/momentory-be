package com.momentory.config;

import com.momentory.MomentoryApplication;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.presentation.ScheduleCompletionRequest;
import com.momentory.user.onboarding.presentation.CompleteOnboardingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = MomentoryApplication.class,
        properties = {
                "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
                "JWT_REFRESH_EXPIRATION=30d",
                "KAKAO_APP_ID=123456789"
        }
)
@Testcontainers(disabledWithoutDocker = true)
class OpenApiContractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
    );

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void documentsDiaryWeather() throws Exception {
        JsonNode docs = getApiDocs();
        String path = "/api/v1/diaries/{id}/weather";
        assertResponseSchema(docs, path, "put", "200", "DiaryResponse");
        assertErrorExample(docs, path, "put", "400", "ApiErrorResponse", "latitudeRange", "INVALID_REQUEST", "위도는 -90부터 90까지입니다.");
        assertErrorExample(docs, path, "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(docs, path, "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(docs, path, "put", "404", "ApiErrorResponse", "DIARY_NOT_FOUND", "DIARY_NOT_FOUND", "일기를 찾을 수 없습니다.");
        assertErrorExample(docs, path, "put", "409", "ApiErrorResponse", "DIARY_WEATHER_DATE_MISMATCH", "DIARY_WEATHER_DATE_MISMATCH", "지난 일기에는 현재 날씨를 기록할 수 없습니다.");
        assertErrorExample(docs, path, "put", "503", "ApiErrorResponse", "WEATHER_UNAVAILABLE", "WEATHER_UNAVAILABLE", "날씨를 가져오지 못했어요. 일기는 그대로 저장돼요.");
        JsonNode properties = docs.path("components").path("schemas").path("DiaryWeatherRequest").path("properties");
        assertThat(properties.size()).isEqualTo(2);
        assertThat(properties.has("latitude")).isTrue();
        assertThat(properties.has("longitude")).isTrue();
        assertThat(docs.path("components").path("schemas").path("DiaryResponse").path("properties").has("weather")).isTrue();
    }

    @Test
    void documentsLetters() throws Exception {
        JsonNode docs = getApiDocs();
        String auth = "AUTHENTICATION_REQUIRED";
        String authMessage = "인증이 필요합니다.";

        String current = "/api/v1/letters/current";
        assertResponseSchema(docs, current, "get", "200", "LetterResponse");
        assertNoResponseContent(docs, current, "get", "204");
        assertErrorExample(docs, current, "get", "404", "ApiErrorResponse", "LETTER_NOT_FOUND", "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");
        assertErrorExample(docs, current, "get", "401", "ApiErrorResponse", auth, auth, authMessage);
        assertOperationTag(docs, current, "get", "Letters");

        String list = "/api/v1/letters";
        assertResponseSchema(docs, list, "get", "200", "LetterListResponse");
        assertErrorExample(docs, list, "get", "401", "ApiErrorResponse", auth, auth, authMessage);

        String byDate = "/api/v1/letters/{date}";
        assertResponseSchema(docs, byDate, "get", "200", "LetterResponse");
        assertErrorExample(docs, byDate, "get", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(docs, byDate, "get", "401", "ApiErrorResponse", auth, auth, authMessage);
        assertErrorExample(docs, byDate, "get", "404", "ApiErrorResponse", "LETTER_NOT_FOUND", "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");

        String open = "/api/v1/letters/{id}/open";
        assertNoResponseContent(docs, open, "post", "204");
        assertErrorExample(docs, open, "post", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(docs, open, "post", "401", "ApiErrorResponse", auth, auth, authMessage);
        assertErrorExample(docs, open, "post", "404", "ApiErrorResponse", "LETTER_NOT_FOUND", "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");

        String action = "/api/v1/letters/{id}/action";
        assertResponseSchema(docs, action, "put", "200", "LetterResponse");
        assertErrorExample(docs, action, "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(docs, action, "put", "400", "ApiErrorResponse", "positionRange", "INVALID_REQUEST", "작은 일 칸은 0부터 2까지입니다.");
        assertErrorExample(docs, action, "put", "400", "ApiErrorResponse", "whenLabelTooLong", "INVALID_REQUEST", "「언제」는 최대 30자입니다.");
        assertErrorExample(docs, action, "put", "400", "ApiErrorResponse", "LETTER_ACTION_NOT_FOUND", "LETTER_ACTION_NOT_FOUND", "편지에 그 작은 일이 없습니다.");
        assertErrorExample(docs, action, "put", "401", "ApiErrorResponse", auth, auth, authMessage);
        assertErrorExample(docs, action, "put", "404", "ApiErrorResponse", "LETTER_NOT_FOUND", "LETTER_NOT_FOUND", "편지를 찾을 수 없습니다.");
        assertErrorExample(docs, action, "put", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");
        JsonNode request = docs.at("/components/schemas/ChooseLetterActionRequest/properties");
        assertThat(request.size()).isEqualTo(4);
        assertThat(request.has("position") && request.has("whenLabel") && request.has("scheduleId") && request.has("remindAt")).isTrue();
        JsonNode response = docs.at("/components/schemas/LetterResponse/properties");
        assertThat(response.has("isNew")).isTrue();
        assertThat(response.has("helplines")).isTrue();

        String letterTime = "/api/v1/users/me/letter-time";
        assertResponseSchema(docs, letterTime, "put", "200", "LetterTimeResponse");
        assertErrorExample(docs, letterTime, "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(docs, letterTime, "put", "400", "ApiErrorResponse", "letterTimeRequired", "INVALID_REQUEST", "letterTime은 필수입니다.");
        assertErrorExample(docs, letterTime, "put", "400", "ApiErrorResponse", "letterTimeFormat", "INVALID_REQUEST", "letterTime은 HH:mm 형식이어야 합니다.");
        assertErrorExample(docs, letterTime, "put", "400", "ApiErrorResponse", "notificationEnabledRequired", "INVALID_REQUEST", "notificationEnabled는 필수입니다.");
        assertErrorExample(docs, letterTime, "put", "401", "ApiErrorResponse", auth, auth, authMessage);
        assertErrorExample(docs, letterTime, "put", "409", "ApiErrorResponse", "ONBOARDING_REQUIRED", "ONBOARDING_REQUIRED", "온보딩을 먼저 마쳐 주세요.");
        assertOperationTag(docs, letterTime, "put", "Users");
        assertThat(docs.at("/components/schemas/LetterTimeRequest/properties").size()).isEqualTo(2);
        JsonNode profile = docs.at("/components/schemas/Profile/properties");
        assertThat(profile.has("letterTime") && profile.has("letterNotificationEnabled")).isTrue();
        assertThat(docs.at("/components/schemas/LetterResponse/properties/status/enum").toString())
                .isEqualTo("[\"READY\",\"FALLBACK\",\"QUIET\"]");
    }

    @Test
    void documentsCurrentWeather() throws Exception {
        JsonNode docs = getApiDocs();
        String path = "/api/v1/weather/current";
        assertResponseSchema(docs, path, "post", "200", "CurrentWeatherResponse");
        assertErrorExample(docs, path, "post", "400", "ApiErrorResponse", "INVALID_COORDINATE", "INVALID_COORDINATE", "좌표가 올바르지 않습니다.");
        assertErrorExample(docs, path, "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(docs, path, "post", "429", "ApiErrorResponse", "TOO_MANY_REQUESTS", "TOO_MANY_REQUESTS", "요청이 너무 많습니다.");
        assertErrorExample(docs, path, "post", "502", "ApiErrorResponse", "WEATHER_UPSTREAM_FAILED", "WEATHER_UPSTREAM_FAILED", "날씨를 가져오지 못했어요.");
        assertErrorExample(docs, path, "post", "503", "ApiErrorResponse", "WEATHER_UNAVAILABLE", "WEATHER_UNAVAILABLE", "날씨 기능을 사용할 수 없습니다.");
        assertErrorExample(docs, path, "post", "504", "ApiErrorResponse", "WEATHER_UPSTREAM_TIMEOUT", "WEATHER_UPSTREAM_TIMEOUT", "날씨를 가져오지 못했어요.");
        JsonNode request = docs.at("/components/schemas/CurrentWeatherRequest/properties");
        assertThat(request.size()).isEqualTo(2);
        assertThat(request.has("latitude")).isTrue();
        assertThat(request.has("longitude")).isTrue();
        assertThat(docs.at("/components/schemas/CurrentWeatherResponse/properties").has("latitude")).isFalse();
    }

    @Test
    void documentsResponseSchemasAndErrorExamplesForPublicApis() throws Exception {
        JsonNode apiDocs = getApiDocs();

        assertResponseSchema(apiDocs, "/api/v1/auth/kakao", "post", "200", "KakaoLoginResponse");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "accessToken은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "400", "ApiErrorResponse", "KAKAO_EMAIL_CONSENT_REQUIRED", "KAKAO_EMAIL_CONSENT_REQUIRED", "카카오계정 이메일 제공 동의가 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "400", "ApiErrorResponse", "KAKAO_EMAIL_UNAVAILABLE", "KAKAO_EMAIL_UNAVAILABLE", "유효하고 인증된 카카오계정 이메일이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "401", "ApiErrorResponse", "KAKAO_TOKEN_INVALID", "KAKAO_TOKEN_INVALID", "카카오 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "401", "ApiErrorResponse", "KAKAO_APP_ID_MISMATCH", "KAKAO_APP_ID_MISMATCH", "카카오 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "401", "ApiErrorResponse", "KAKAO_USER_ID_MISMATCH", "KAKAO_USER_ID_MISMATCH", "카카오 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "502", "ApiErrorResponse", "KAKAO_API_SERVER_ERROR", "KAKAO_API_SERVER_ERROR", "카카오 서비스에 일시적인 오류가 발생했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "502", "ApiErrorResponse", "KAKAO_API_RESPONSE_ERROR", "KAKAO_API_RESPONSE_ERROR", "카카오 서비스 응답을 처리할 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/kakao", "post", "503", "ApiErrorResponse", "KAKAO_API_NETWORK_ERROR", "KAKAO_API_NETWORK_ERROR", "카카오 서비스에 연결할 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/auth/apple", "post", "200", "AppleLoginResponse");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "identityToken은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "400", "ApiErrorResponse", "APPLE_EMAIL_UNAVAILABLE", "APPLE_EMAIL_UNAVAILABLE", "유효하고 인증된 애플계정 이메일이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "401", "ApiErrorResponse", "APPLE_TOKEN_INVALID", "APPLE_TOKEN_INVALID", "애플 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "401", "ApiErrorResponse", "APPLE_NONCE_MISMATCH", "APPLE_NONCE_MISMATCH", "애플 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "401", "ApiErrorResponse", "APPLE_CLIENT_ID_MISMATCH", "APPLE_CLIENT_ID_MISMATCH", "애플 인증에 실패했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "502", "ApiErrorResponse", "APPLE_API_SERVER_ERROR", "APPLE_API_SERVER_ERROR", "애플 서비스에 일시적인 오류가 발생했습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "502", "ApiErrorResponse", "APPLE_API_RESPONSE_ERROR", "APPLE_API_RESPONSE_ERROR", "애플 서비스 응답을 처리할 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/apple", "post", "503", "ApiErrorResponse", "APPLE_API_NETWORK_ERROR", "APPLE_API_NETWORK_ERROR", "애플 서비스에 연결할 수 없습니다.");
        assertOperationTag(apiDocs, "/api/v1/auth/apple", "post", "Authentication");

        assertResponseSchema(apiDocs, "/api/v1/auth/reissue", "post", "200", "RefreshTokenReissueResponse");
        assertErrorExample(apiDocs, "/api/v1/auth/reissue", "post", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "refreshToken은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/reissue", "post", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/reissue", "post", "401", "ApiErrorResponse", "REFRESH_TOKEN_INVALID", "REFRESH_TOKEN_INVALID", "리프레시 토큰이 유효하지 않습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/reissue", "post", "401", "ApiErrorResponse", "REFRESH_TOKEN_REVOKED", "REFRESH_TOKEN_REVOKED", "리프레시 토큰이 이미 폐기되었습니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/reissue", "post", "401", "ApiErrorResponse", "REFRESH_TOKEN_EXPIRED", "REFRESH_TOKEN_EXPIRED", "리프레시 토큰이 만료되었습니다.");
        assertOperationTag(apiDocs, "/api/v1/auth/reissue", "post", "Authentication");

        assertNoResponseContent(apiDocs, "/api/v1/auth/logout", "post", "204");
        assertErrorExample(apiDocs, "/api/v1/auth/logout", "post", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "refreshToken은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/auth/logout", "post", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertOperationTag(apiDocs, "/api/v1/auth/logout", "post", "Authentication");
    }

    @Test
    void documentsResponseSchemasAndErrorExamplesForProtectedApis() throws Exception {
        JsonNode apiDocs = getApiDocs();

        assertResponseSchema(apiDocs, "/api/v1/users/me", "get", "200", "UserMeResponse");
        assertErrorExample(apiDocs, "/api/v1/users/me", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertNoResponseContent(apiDocs, "/api/v1/users/me", "delete", "204");
        assertErrorExample(apiDocs, "/api/v1/users/me", "delete", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/users/me", "delete", "502", "ApiErrorResponse", "KAKAO_API_SERVER_ERROR", "KAKAO_API_SERVER_ERROR", "카카오 서비스에 일시적인 오류가 발생했습니다.");
        assertErrorExample(apiDocs, "/api/v1/users/me", "delete", "502", "ApiErrorResponse", "KAKAO_API_RESPONSE_ERROR", "KAKAO_API_RESPONSE_ERROR", "카카오 서비스 응답을 처리할 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/users/me", "delete", "503", "ApiErrorResponse", "KAKAO_API_NETWORK_ERROR", "KAKAO_API_NETWORK_ERROR", "카카오 서비스에 연결할 수 없습니다.");
        assertOperationTag(apiDocs, "/api/v1/users/me", "delete", "Users");

        assertResponseSchema(apiDocs, "/api/v1/users/me/onboarding", "put", "200", "CompleteOnboardingResponse");
        assertOnboardingRequestProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/users/me/onboarding", "put", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "nickname은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/users/me/onboarding", "put", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/users/me/onboarding", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v2/users/me/onboarding", "put", "200", "CompleteOnboardingV2Response");
        assertOnboardingV2Properties(apiDocs);
        assertErrorExample(apiDocs, "/api/v2/users/me/onboarding", "put", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "nickname은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v2/users/me/onboarding", "put", "400", "ApiErrorResponse", "unreadableRequest", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v2/users/me/onboarding", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertOperationTag(apiDocs, "/api/v2/users/me/onboarding", "put", "Users");

        assertResponseSchema(apiDocs, "/api/v1/onboarding/options", "get", "200", "OnboardingOptionsResponse");
        assertErrorExample(apiDocs, "/api/v1/onboarding/options", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/schedules", "get", "200", "ScheduleListResponse");
        assertScheduleResponseProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/schedules", "get", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertResponseSchema(apiDocs, "/api/v1/schedules/period", "get", "200", "ScheduleListResponse");
        assertErrorExample(apiDocs, "/api/v1/schedules/period", "get", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/period", "get", "400", "ApiErrorResponse", "invalidPeriodRange", "INVALID_REQUEST", "일정 조회 기간이 올바르지 않습니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/period", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertResponseSchema(apiDocs, "/api/v1/schedules", "post", "201", "ScheduleResponse");
        assertErrorExample(apiDocs, "/api/v1/schedules", "post", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "title은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertResponseSchema(apiDocs, "/api/v1/schedules/sync", "post", "200", "CalendarSyncResponse");
        assertCalendarSyncRequestProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/schedules/sync", "post", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "externalId는 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/sync", "post", "400", "ApiErrorResponse", "invalidSyncRange", "INVALID_REQUEST", "캘린더 동기화 요청이 올바르지 않습니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/sync", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertResponseSchema(apiDocs, "/api/v1/schedules/{scheduleId}", "patch", "200", "ScheduleResponse");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "patch", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "title은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "patch", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "patch", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");
        assertNoResponseContent(apiDocs, "/api/v1/schedules/{scheduleId}", "delete", "204");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "delete", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "delete", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}", "delete", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");
        assertNoResponseContent(apiDocs, "/api/v1/schedules/{scheduleId}/visibility", "patch", "204");
        assertVisibilityRequestProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/visibility", "patch", "400", "ApiErrorResponse", "validationError", "INVALID_REQUEST", "hidden은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/visibility", "patch", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/visibility", "patch", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/schedules/{scheduleId}/completion", "put", "200", "ScheduleCompletionResponse");
        assertScheduleCompletionRequestProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/completion", "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/completion", "put", "400", "ApiErrorResponse", "incompleteScheduleWithEmotion", "INVALID_REQUEST", "미완료 상태에서는 감정을 선택할 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/completion", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/{scheduleId}/completion", "put", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");

        assertNoResponseContent(apiDocs, "/api/v1/schedules/order", "patch", "204");
        assertErrorExample(apiDocs, "/api/v1/schedules/order", "patch", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/order", "patch", "400", "ApiErrorResponse", "invalidScheduleOrder", "INVALID_REQUEST", "일정 순서 변경 요청이 올바르지 않습니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/order", "patch", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/schedules/order", "patch", "404", "ApiErrorResponse", "SCHEDULE_NOT_FOUND", "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/memos/{date}", "get", "200", "DailyMemoResponse");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "get", "400", "ApiErrorResponse", "invalidDateFormat", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "get", "404", "ApiErrorResponse", "DAILY_MEMO_NOT_FOUND", "DAILY_MEMO_NOT_FOUND", "하루 메모를 찾을 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/memos/{date}", "put", "200", "DailyMemoResponse");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "put", "400", "ApiErrorResponse", "invalidDateFormat", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "put", "400", "ApiErrorResponse", "malformedRequestBody", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "put", "400", "ApiErrorResponse", "contentRequiredOrBlank", "INVALID_REQUEST", "메모 내용을 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertNoResponseContent(apiDocs, "/api/v1/memos/{date}", "delete", "204");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "delete", "400", "ApiErrorResponse", "invalidDateFormat", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/memos/{date}", "delete", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/treasures", "post", "201", "TreasureResponse");
        assertErrorExample(apiDocs, "/api/v1/treasures", "post", "400", "ApiErrorResponse", "malformedRequestBody", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/treasures", "post", "400", "ApiErrorResponse", "contentRequiredOrBlank", "INVALID_REQUEST", "보물 내용을 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/treasures", "post", "400", "ApiErrorResponse", "contentTooLong", "INVALID_REQUEST", "보물 내용은 최대 100자입니다.");
        assertErrorExample(apiDocs, "/api/v1/treasures", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/treasures", "get", "200", "TreasureListResponse");
        assertErrorExample(apiDocs, "/api/v1/treasures", "get", "400", "ApiErrorResponse", "invalidDateFormat", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/treasures", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/worries", "post", "201", "WorryResponse");
        assertErrorExample(apiDocs, "/api/v1/worries", "post", "400", "ApiErrorResponse", "malformedRequestBody", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/worries", "post", "400", "ApiErrorResponse", "contentRequiredOrBlank", "INVALID_REQUEST", "걱정 내용을 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/worries", "post", "400", "ApiErrorResponse", "contentTooLong", "INVALID_REQUEST", "걱정 내용은 최대 100자입니다.");
        assertErrorExample(apiDocs, "/api/v1/worries", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/worries", "get", "200", "WorryListResponse");
        assertErrorExample(apiDocs, "/api/v1/worries", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/inquiries", "post", "201", "InquiryResponse");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "malformedRequestBody", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "categoryRequired", "INVALID_REQUEST", "문의 종류를 골라주세요.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "contentRequiredOrBlank", "INVALID_REQUEST", "문의 내용을 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "contentTooLong", "INVALID_REQUEST", "문의 내용은 최대 2000자입니다.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "invalidReplyEmail", "INVALID_REQUEST", "이메일 형식이 올바르지 않습니다.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "400", "ApiErrorResponse", "deviceInfoTooLong", "INVALID_REQUEST", "기기 정보는 최대 30자입니다.");
        assertErrorExample(apiDocs, "/api/v1/inquiries", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/rest-records", "post", "201", "RestRecordResponse");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "400", "ApiErrorResponse", "malformedRequestBody", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "400", "ApiErrorResponse", "contentRequired", "INVALID_REQUEST", "쉼터를 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "400", "ApiErrorResponse", "completedRequired", "INVALID_REQUEST", "끝까지 했는지 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "400", "ApiErrorResponse", "completedWithoutMood", "INVALID_REQUEST", "끝까지 했을 때는 기분을 골라주세요.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "400", "ApiErrorResponse", "incompleteWithMood", "INVALID_REQUEST", "끝까지 하지 않았을 때는 기분을 남길 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/rest-records", "get", "200", "RestRecordListResponse");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "get", "400", "ApiErrorResponse", "invalidContent", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/rest-records", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/retrospect", "post", "201", "StartRetrospectResponse");
        assertStartRetrospectRequestProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/retrospect", "post", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "currentEmotion은 필수입니다.");
        assertErrorExample(apiDocs, "/api/v1/retrospect", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertResponseSchema(apiDocs, "/api/v1/retrospect/{id}/messages", "post", "200", "ReplyDto");
        assertErrorExample(apiDocs, "/api/v1/retrospect/{id}/messages", "post", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/retrospect/{id}/messages", "post", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/retrospect/{id}/messages", "post", "404", "ApiErrorResponse", "RETROSPECT_SESSION_NOT_FOUND", "RETROSPECT_SESSION_NOT_FOUND", "회고 세션을 찾을 수 없습니다.");

        assertNoResponseContent(apiDocs, "/api/v1/diaries/{id}", "delete", "204");
        assertErrorExample(apiDocs, "/api/v1/diaries/{id}", "delete", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/diaries/{id}", "delete", "404", "ApiErrorResponse", "DIARY_NOT_FOUND", "DIARY_NOT_FOUND", "일기를 찾을 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/diaries/by-retrospect/{retrospectId}", "put", "200", "DiaryResponse");
        assertErrorExample(apiDocs, "/api/v1/diaries/by-retrospect/{retrospectId}", "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "일기 본문을 입력해주세요.");
        assertErrorExample(apiDocs, "/api/v1/diaries/by-retrospect/{retrospectId}", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/diaries/by-retrospect/{retrospectId}", "put", "404", "ApiErrorResponse", "DIARY_NOT_FOUND", "DIARY_NOT_FOUND", "일기를 찾을 수 없습니다.");

        assertResponseSchema(apiDocs, "/api/v1/reports/weekly", "get", "200", "WeeklyReportResponse");
        assertWeeklyReportResponseProperties(apiDocs);
        assertErrorExample(apiDocs, "/api/v1/reports/weekly", "get", "400", "ApiErrorResponse", "invalidDateFormat", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertErrorExample(apiDocs, "/api/v1/reports/weekly", "get", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertOperationTag(apiDocs, "/api/v1/reports/weekly", "get", "Reports");

        assertResponseSchema(apiDocs, "/api/v1/action-cards/{id}/completion", "put", "200", "ActionCardCompletionResponse");
        assertErrorExample(apiDocs, "/api/v1/action-cards/{id}/completion", "put", "400", "ApiErrorResponse", "INVALID_REQUEST", "INVALID_REQUEST", "되돌린 상태에서는 느낀 점을 남길 수 없습니다.");
        assertErrorExample(apiDocs, "/api/v1/action-cards/{id}/completion", "put", "401", "ApiErrorResponse", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertErrorExample(apiDocs, "/api/v1/action-cards/{id}/completion", "put", "404", "ApiErrorResponse", "ACTION_CARD_NOT_FOUND", "ACTION_CARD_NOT_FOUND", "행동 카드를 찾을 수 없습니다.");
    }

    @Test
    void hidesInternalCompletionValidationPropertyFromJackson() throws Exception {
        ScheduleCompletionRequest request = objectMapper.readValue("""
                {
                  "completed": true,
                  "emotion": "PROUD",
                  "completionEmotionValid": false
                }
                """, ScheduleCompletionRequest.class);

        assertThat(request.completed()).isTrue();
        assertThat(request.emotion()).isEqualTo(ScheduleEmotion.PROUD);
        assertThat(request.isCompletionEmotionValid()).isTrue();

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsString(request));
        assertThat(serialized.size()).isEqualTo(2);
        assertThat(serialized.has("completed")).isTrue();
        assertThat(serialized.has("emotion")).isTrue();
        assertThat(serialized.has("completionEmotionValid")).isFalse();

        CompleteOnboardingRequest onboardingRequest = objectMapper.readValue("""
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["SELF"],
                 "restMethods":["READING"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """, CompleteOnboardingRequest.class);
        JsonNode onboardingSerialized = objectMapper.readTree(objectMapper.writeValueAsString(onboardingRequest));
        assertThat(onboardingSerialized.has("otherInterestDetailValid")).isFalse();
        assertThat(onboardingSerialized.has("otherRestMethodDetailValid")).isFalse();
    }

    @Test
    void documentsOnlyImplementedErrorStatusCodesAndSharedErrorProperties() throws Exception {
        JsonNode apiDocs = getApiDocs();

        for (ApiOperation operation : ApiOperation.values()) {
            JsonNode responses = operationResponses(apiDocs, operation.path(), operation.method());
            assertThat(responses.has("403")).isFalse();
            assertThat(responses.has("500")).isFalse();
            if (!operation.allowsNotFound()) {
                assertThat(responses.has("404")).isFalse();
            }
            if (!operation.allowsConflict()) {
                assertThat(responses.has("409")).isFalse();
            }
        }

        assertErrorSchemaProperties(apiDocs, "ApiErrorResponse");
    }

    private JsonNode getApiDocs() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertResponseSchema(
            JsonNode apiDocs,
            String path,
            String method,
            String responseCode,
            String expectedSchema
    ) {
        JsonNode response = response(apiDocs, path, method, responseCode);
        assertThat(response.at("/content/application~1json/schema/$ref").stringValue())
                .isEqualTo("#/components/schemas/" + expectedSchema);
    }

    private void assertErrorResponse(
            JsonNode apiDocs,
            String path,
            String method,
            String responseCode,
            String expectedSchema
    ) {
        JsonNode response = response(apiDocs, path, method, responseCode);
        assertResponseSchema(apiDocs, path, method, responseCode, expectedSchema);

        JsonNode examples = response.at("/content/application~1json/examples");
        assertThat(examples.isObject()).isTrue();
        Iterator<Map.Entry<String, JsonNode>> iterator = examples.properties().iterator();
        assertThat(iterator.hasNext()).isTrue();
        while (iterator.hasNext()) {
            JsonNode value = iterator.next().getValue().path("value");
            assertThat(value.path("code").isTextual()).isTrue();
            assertThat(value.path("message").isTextual()).isTrue();
        }
    }

    private void assertErrorExample(
            JsonNode apiDocs,
            String path,
            String method,
            String responseCode,
            String expectedSchema,
            String exampleName,
            String expectedCode,
            String expectedMessage
    ) {
        assertResponseSchema(apiDocs, path, method, responseCode, expectedSchema);
        JsonNode value = response(apiDocs, path, method, responseCode)
                .at("/content/application~1json/examples/" + exampleName + "/value");
        assertThat(value.path("code").stringValue()).isEqualTo(expectedCode);
        assertThat(value.path("message").stringValue()).isEqualTo(expectedMessage);
    }

    private void assertNoResponseContent(JsonNode apiDocs, String path, String method, String responseCode) {
        assertThat(response(apiDocs, path, method, responseCode).has("content")).isFalse();
    }

    private void assertOperationTag(JsonNode apiDocs, String path, String method, String expectedTag) {
        assertThat(apiDocs.at("/paths/" + escapeJsonPointer(path) + "/" + method + "/tags/0").stringValue())
                .isEqualTo(expectedTag);
    }

    private void assertErrorSchemaProperties(JsonNode apiDocs, String schemaName) {
        JsonNode properties = apiDocs.at("/components/schemas/" + schemaName + "/properties");
        assertThat(properties.has("code")).isTrue();
        assertThat(properties.has("message")).isTrue();
    }

    private void assertScheduleCompletionRequestProperties(JsonNode apiDocs) {
        JsonNode properties = apiDocs.at("/components/schemas/ScheduleCompletionRequest/properties");
        assertThat(properties.isObject()).isTrue();
        assertThat(properties.size()).isEqualTo(2);
        assertThat(properties.has("completed")).isTrue();
        assertThat(properties.has("emotion")).isTrue();
        assertThat(properties.has("completionEmotionValid")).isFalse();
    }

    private void assertStartRetrospectRequestProperties(JsonNode apiDocs) {
        JsonNode request = apiDocs.at("/components/schemas/StartRetrospectRequest/properties");
        assertThat(request.has("schedules")).isTrue();

        // 일정 한 줄 — `completed` 는 소재를 고르는 우선순위(관심분야 > 완료)에 쓰인다.
        // 클라이언트가 보내는 필드가 넷임을 계약으로 못박는다.
        JsonNode item = apiDocs.at("/components/schemas/ScheduleItemRequest/properties");
        assertThat(item.size()).isEqualTo(4);
        assertThat(item.has("id")).isTrue();
        assertThat(item.has("name")).isTrue();
        assertThat(item.has("emotion")).isTrue();
        assertThat(item.has("completed")).isTrue();
    }

    private void assertCalendarSyncRequestProperties(JsonNode apiDocs) {
        JsonNode request = apiDocs.at("/components/schemas/CalendarSyncRequest/properties");
        assertThat(request.size()).isEqualTo(3);
        assertThat(request.has("from")).isTrue();
        assertThat(request.has("to")).isTrue();
        assertThat(request.has("events")).isTrue();

        JsonNode event = apiDocs.at("/components/schemas/CalendarSyncEventRequest/properties");
        assertThat(event.size()).isEqualTo(3);
        assertThat(event.has("externalId")).isTrue();
        assertThat(event.has("date")).isTrue();
        assertThat(event.has("title")).isTrue();
    }

    private void assertWeeklyReportResponseProperties(JsonNode apiDocs) {
        JsonNode properties = apiDocs.at("/components/schemas/WeeklyReportResponse/properties");
        assertThat(properties.size()).isEqualTo(12);
        assertThat(properties.has("startDate")).isTrue();
        assertThat(properties.has("endDate")).isTrue();
        assertThat(properties.has("dailyMoods")).isTrue();
        assertThat(properties.has("dominantEmotion")).isTrue();
        assertThat(properties.has("moodMessage")).isTrue();
        assertThat(properties.has("scheduleTotalCount")).isTrue();
        assertThat(properties.has("scheduleCompletedCount")).isTrue();
        assertThat(properties.has("actionCardCreatedCount")).isTrue();
        assertThat(properties.has("actionCardCompletedCount")).isTrue();
        // 「이번 주 바람카드」 — 찾은 바람과 실천한 것이 한 응답에 함께 온다
        assertThat(properties.has("needs")).isTrue();
        assertThat(properties.has("practicedWishes")).isTrue();
        assertThat(properties.has("diaryCount")).isTrue();

        JsonNode need = apiDocs.at("/components/schemas/NeedCountResponse/properties");
        assertThat(need.size()).isEqualTo(2);
        assertThat(need.has("word")).isTrue();
        assertThat(need.has("count")).isTrue();

        JsonNode practiced = apiDocs.at("/components/schemas/PracticedWishResponse/properties");
        assertThat(practiced.size()).isEqualTo(2);
        assertThat(practiced.has("action")).isTrue();
        assertThat(practiced.has("needs")).isTrue();

        JsonNode daily = apiDocs.at("/components/schemas/DailyMoodResponse/properties");
        assertThat(daily.size()).isEqualTo(3);
        assertThat(daily.has("date")).isTrue();
        // 점은 하루 하나라 대표 감정이 색을 맡고, 나머지는 눌러야 보인다 — 둘 다 계약이다
        assertThat(daily.has("emotion")).isTrue();
        assertThat(daily.has("emotions")).isTrue();
    }

    private void assertScheduleResponseProperties(JsonNode apiDocs) {
        JsonNode properties = apiDocs.at("/components/schemas/ScheduleResponse/properties");
        assertThat(properties.has("source")).isTrue();
        assertThat(properties.has("hidden")).isTrue();
        assertThat(properties.has("externalId")).isFalse();
    }

    private void assertVisibilityRequestProperties(JsonNode apiDocs) {
        JsonNode properties = apiDocs.at("/components/schemas/ScheduleVisibilityRequest/properties");
        assertThat(properties.size()).isEqualTo(1);
        assertThat(properties.has("hidden")).isTrue();
    }

    private void assertOnboardingRequestProperties(JsonNode apiDocs) {
        JsonNode properties = apiDocs.at("/components/schemas/CompleteOnboardingRequest/properties");
        assertThat(properties.has("otherInterestDetail")).isTrue();
        assertThat(properties.has("restMethods")).isTrue();
        assertThat(properties.has("otherRestMethodDetail")).isTrue();
        assertThat(properties.has("notificationEnabled")).isTrue();
        assertThat(properties.has("otherInterestDetailValid")).isFalse();
        assertThat(properties.has("otherRestMethodDetailValid")).isFalse();
    }

    private void assertOnboardingV2Properties(JsonNode apiDocs) {
        JsonNode request = apiDocs.at("/components/schemas/CompleteOnboardingV2Request/properties");
        assertThat(request.has("moodChange")).isTrue();
        assertThat(request.has("knowWhy")).isTrue();
        assertThat(request.has("dayEnd")).isTrue();
        assertThat(request.has("diaryHistory")).isTrue();
        assertThat(request.has("goals")).isTrue();
        assertThat(request.has("interestAreas")).isFalse();
        assertThat(request.has("restMethods")).isFalse();
        assertThat(apiDocs.at("/components/schemas/CompleteOnboardingV2Response/properties").has("onboardingVersion")).isTrue();
        JsonNode userMe = apiDocs.at("/components/schemas/UserMeResponse/properties");
        assertThat(userMe.has("onboardingVersion")).isTrue();
        assertThat(userMe.has("latestOnboardingVersion")).isTrue();
    }

    private JsonNode response(JsonNode apiDocs, String path, String method, String responseCode) {
        JsonNode response = operationResponses(apiDocs, path, method).path(responseCode);
        assertThat(response.isObject()).isTrue();
        return response;
    }

    private JsonNode operationResponses(JsonNode apiDocs, String path, String method) {
        JsonNode responses = apiDocs.at("/paths/" + escapeJsonPointer(path) + "/" + method + "/responses");
        assertThat(responses.isObject()).isTrue();
        return responses;
    }

    private String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private enum ApiOperation {
        KAKAO_LOGIN("/api/v1/auth/kakao", "post"),
        APPLE_LOGIN("/api/v1/auth/apple", "post"),
        TOKEN_REISSUE("/api/v1/auth/reissue", "post"),
        LOGOUT("/api/v1/auth/logout", "post"),
        USER_ME("/api/v1/users/me", "get"),
        USER_WITHDRAWAL("/api/v1/users/me", "delete"),
        ONBOARDING_COMPLETE("/api/v1/users/me/onboarding", "put"),
        ONBOARDING_V2_COMPLETE("/api/v2/users/me/onboarding", "put"),
        ONBOARDING_OPTIONS("/api/v1/onboarding/options", "get"),
        SCHEDULES_GET("/api/v1/schedules", "get"),
        SCHEDULES_PERIOD("/api/v1/schedules/period", "get"),
        SCHEDULES_POST("/api/v1/schedules", "post"),
        SCHEDULES_SYNC("/api/v1/schedules/sync", "post"),
        SCHEDULES_PATCH("/api/v1/schedules/{scheduleId}", "patch"),
        SCHEDULES_DELETE("/api/v1/schedules/{scheduleId}", "delete"),
        SCHEDULES_VISIBILITY("/api/v1/schedules/{scheduleId}/visibility", "patch"),
        SCHEDULES_COMPLETION("/api/v1/schedules/{scheduleId}/completion", "put"),
        SCHEDULES_ORDER("/api/v1/schedules/order", "patch"),
        DAILY_MEMO_GET("/api/v1/memos/{date}", "get"),
        DAILY_MEMO_PUT("/api/v1/memos/{date}", "put"),
        DAILY_MEMO_DELETE("/api/v1/memos/{date}", "delete"),
        RETROSPECT_START("/api/v1/retrospect", "post"),
        RETROSPECT_MESSAGE("/api/v1/retrospect/{id}/messages", "post"),
        DIARY_DELETE("/api/v1/diaries/{id}", "delete"),
        DIARY_UPDATE_BY_RETROSPECT("/api/v1/diaries/by-retrospect/{retrospectId}", "put"),
        ACTION_CARD_COMPLETION("/api/v1/action-cards/{id}/completion", "put"),
        REPORT_WEEKLY("/api/v1/reports/weekly", "get");

        private final String path;
        private final String method;

        ApiOperation(String path, String method) {
            this.path = path;
            this.method = method;
        }

        String path() {
            return path;
        }

        String method() {
            return method;
        }

        boolean allowsNotFound() {
            return this == SCHEDULES_PATCH
                    || this == SCHEDULES_DELETE
                    || this == SCHEDULES_VISIBILITY
                    || this == SCHEDULES_COMPLETION
                    || this == SCHEDULES_ORDER
                    || this == DAILY_MEMO_GET
                    || this == RETROSPECT_MESSAGE
                    || this == DIARY_DELETE
                    || this == DIARY_UPDATE_BY_RETROSPECT
                    || this == ACTION_CARD_COMPLETION;
        }

        /** 회고 시작은 "하루 한 번" 가드로 409(오늘 이미 완료)를 낸다. */
        boolean allowsConflict() {
            return this == RETROSPECT_START;
        }
    }
}
