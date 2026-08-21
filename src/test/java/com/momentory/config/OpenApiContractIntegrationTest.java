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

        assertResponseSchema(apiDocs, "/api/v1/retrospect", "post", "201", "StartRetrospectResponse");
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
        assertThat(properties.size()).isEqualTo(10);
        assertThat(properties.has("startDate")).isTrue();
        assertThat(properties.has("endDate")).isTrue();
        assertThat(properties.has("dailyMoods")).isTrue();
        assertThat(properties.has("dominantEmotion")).isTrue();
        assertThat(properties.has("moodMessage")).isTrue();
        assertThat(properties.has("scheduleTotalCount")).isTrue();
        assertThat(properties.has("scheduleCompletedCount")).isTrue();
        assertThat(properties.has("actionCardCreatedCount")).isTrue();
        assertThat(properties.has("actionCardCompletedCount")).isTrue();
        assertThat(properties.has("diaryCount")).isTrue();

        JsonNode daily = apiDocs.at("/components/schemas/DailyMoodResponse/properties");
        assertThat(daily.size()).isEqualTo(2);
        assertThat(daily.has("date")).isTrue();
        assertThat(daily.has("emotion")).isTrue();
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
        TOKEN_REISSUE("/api/v1/auth/reissue", "post"),
        LOGOUT("/api/v1/auth/logout", "post"),
        USER_ME("/api/v1/users/me", "get"),
        USER_WITHDRAWAL("/api/v1/users/me", "delete"),
        ONBOARDING_COMPLETE("/api/v1/users/me/onboarding", "put"),
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
