package com.momentory.user.onboarding.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.TimeZonePolicy;
import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class UserOnboardingControllerIntegrationTest {

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

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired UserProfileRepository userProfileRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired ObjectMapper objectMapper;
    @MockitoSpyBean UserProfileRepository userProfileRepositorySpy;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        reset(userProfileRepositorySpy);
        userProfileRepository.deleteAll();
        userRepository.deleteAllInBatch();
    }

    @Test
    void savesProfileInterestAreasAndCompletedOnboardingState() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, requestBody(" 모리 ", "25", "FEMALE", "[\"CAREER\", \"CAREER\", \"RELATIONSHIP\"]", "21:30", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.onboardingRequired").value(false));

        UserProfile profile = findProfileWithInterestAreas(user.getId());
        assertThat(profile.getNickname()).isEqualTo("모리");
        assertThat(profile.getAge()).isEqualTo(25);
        assertThat(profile.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(profile.getInterestAreas()).containsExactlyInAnyOrder(InterestArea.CAREER, InterestArea.RELATIONSHIP);
        assertThat(profile.getReflectionTime()).isEqualTo(LocalTime.of(21, 30));
        assertThat(profile.getTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(profile.isCalendarIntegrationEnabled()).isTrue();
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.getUpdatedAt()).isNotNull();
        User persistedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(persistedUser.requiresOnboarding()).isFalse();
        assertThat(persistedUser.getCreatedAt()).isNotNull();
        assertThat(persistedUser.getUpdatedAt()).isNotNull();
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(TimeZonePolicy.DEFAULT_ZONE_ID);
        assertThat(objectMapper.serializationConfig().getTimeZone().toZoneId()).isEqualTo(TimeZonePolicy.DEFAULT_ZONE_ID);
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false));
    }

    @Test
    void allowsNullAge() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, requestBody("모리", "null", "UNSPECIFIED", "[\"SELF\"]", "09:30", false)))
                .andExpect(status().isOk());

        assertThat(userProfileRepository.findById(user.getId()).orElseThrow().getAge()).isNull();
    }

    @Test
    void savesOtherDetailsRestMethodsAndNotificationPreference() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE",
                 "interestAreas":["CAREER","OTHER"],"otherInterestDetail":"에세이 글쓰기",
                 "restMethods":["READING","OTHER","READING"],"otherRestMethodDetail":"따뜻한 차 마시기",
                 "reflectionTime":"21:30","calendarIntegrationEnabled":true,"notificationEnabled":true}
                """))
                .andExpect(status().isOk());

        UserProfile profile = findProfileWithCollections(user.getId());
        assertThat(profile.getInterestAreas()).containsExactlyInAnyOrder(InterestArea.CAREER, InterestArea.OTHER);
        assertThat(profile.getOtherInterestDetail()).isEqualTo("에세이 글쓰기");
        assertThat(profile.getRestMethods()).containsExactlyInAnyOrder(RestMethod.READING, RestMethod.OTHER);
        assertThat(profile.getOtherRestMethodDetail()).isEqualTo("따뜻한 차 마시기");
        assertThat(profile.isNotificationEnabled()).isTrue();
    }

    @Test
    void preservesOptionalPreferencesWhenOlderClientOmitsThem() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE",
                 "interestAreas":["SELF"],"restMethods":["READING"],
                 "reflectionTime":"21:30","calendarIntegrationEnabled":true,"notificationEnabled":true}
                """))
                .andExpect(status().isOk());
        mockMvc.perform(onboarding(user, requestBody("새모리", "25", "FEMALE", "[\"SELF\"]", "21:30", false)))
                .andExpect(status().isOk());

        UserProfile profile = findProfileWithCollections(user.getId());
        assertThat(profile.getRestMethods()).containsExactly(RestMethod.READING);
        assertThat(profile.isNotificationEnabled()).isTrue();
    }

    @Test
    void replacesProfileAndInterestAreasWhenCalledAgain() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, requestBody("모리", "25", "FEMALE", "[\"CAREER\", \"STUDY\"]", "21:30", true)))
                .andExpect(status().isOk());
        mockMvc.perform(onboarding(user, requestBody("새모리", "null", "MALE", "[\"HEALTH\"]", "09:00", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false));

        UserProfile profile = findProfileWithInterestAreas(user.getId());
        assertThat(profile.getNickname()).isEqualTo("새모리");
        assertThat(userProfileRepository.count()).isEqualTo(1);
        assertThat(profile.getAge()).isNull();
        assertThat(profile.getGender()).isEqualTo(Gender.MALE);
        assertThat(profile.getInterestAreas()).containsExactly(InterestArea.HEALTH);
        assertThat(profile.getReflectionTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(profile.isCalendarIntegrationEnabled()).isFalse();
    }

    @Test
    void rollsBackUserCompletionWhenProfileStorageFails() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        doThrow(new DataIntegrityViolationException("simulated profile save failure"))
                .when(userProfileRepositorySpy).save(any(UserProfile.class));

        assertThatThrownBy(() -> mockMvc.perform(onboarding(
                user,
                requestBody("모리", "25", "FEMALE", "[\"CAREER\"]", "21:30", true)
        ))).hasCauseInstanceOf(DataIntegrityViolationException.class);

        reset(userProfileRepositorySpy);
        assertThat(userRepository.findById(user.getId()).orElseThrow().requiresOnboarding()).isTrue();
        assertThat(userProfileRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void rejectsMissingAndInvalidAccessTokensUsingExistingAuthenticationResponse() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String body = requestBody("모리", "25", "FEMALE", "[\"CAREER\"]", "21:30", true);

        mockMvc.perform(put("/api/v1/users/me/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(put("/api/v1/users/me/onboarding")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/onboarding/options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsMissingOrNullGenderAndInvalidInterestAreas() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"interestAreas":["CAREER"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":null,"interestAreas":["CAREER"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, requestBody("모리", "25", "FEMALE", "[]", "21:30", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":null,"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["UNKNOWN"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsInconsistentOtherDetailsAndInvalidRestMethods() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["OTHER"],
                 "restMethods":["READING"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("otherInterestDetail은 interestAreas에 OTHER가 포함된 경우에만 필수입니다."));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["SELF"],
                 "restMethods":["OTHER"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("otherRestMethodDetail은 restMethods에 OTHER가 포함된 경우에만 필수입니다."));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["SELF"],
                 "restMethods":[],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["SELF"],
                 "restMethods":[null],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    void rejectsNullBlankOrTooLongNicknameAndInvalidAgeOrCalendarIntegration() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, """
                {"nickname":null,"age":25,"gender":"FEMALE","interestAreas":["CAREER"],"reflectionTime":"21:30","calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, requestBody("   ", "25", "FEMALE", "[\"CAREER\"]", "21:30", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, requestBody("12345678901", "25", "FEMALE", "[\"CAREER\"]", "21:30", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, requestBody("모리", "0", "FEMALE", "[\"CAREER\"]", "21:30", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, requestBody("모리", "121", "FEMALE", "[\"CAREER\"]", "21:30", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["CAREER"],"reflectionTime":"21:30"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsStrictReflectionTimeBoundaries() throws Exception {
        for (String reflectionTime : List.of("00:00", "09:30", "23:59")) {
            User user = userRepository.saveAndFlush(User.create());

            mockMvc.perform(onboarding(user, requestBody("모리", "25", "FEMALE", "[\"CAREER\"]", reflectionTime, true)))
                    .andExpect(status().isOk());
            assertThat(userProfileRepository.findById(user.getId()).orElseThrow().getReflectionTime())
                    .isEqualTo(LocalTime.parse(reflectionTime));
        }
    }

    @Test
    void rejectsInvalidReflectionTimeRepresentations() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        for (String reflectionTime : List.of("9:30", "21:30:00", "24:00", "25:10", "")) {
            mockMvc.perform(onboarding(user, requestBody("모리", "25", "FEMALE", "[\"CAREER\"]", reflectionTime, true)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["CAREER"],"reflectionTime":null,"calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["CAREER"],"calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(onboarding(user, """
                {"nickname":"모리","age":25,"gender":"FEMALE","interestAreas":["CAREER"],"reflectionTime":[21,30],"calendarIntegrationEnabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsOnboardingOptionsWithCodesAndLabels() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        MvcResult result = mockMvc.perform(get("/api/v1/onboarding/options")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname.maxLength").value(10))
                .andExpect(jsonPath("$.nickname.duplicateAllowed").value(true))
                .andExpect(jsonPath("$.genders.length()").value(Gender.values().length))
                .andExpect(jsonPath("$.interestAreas.length()").value(InterestArea.values().length))
                .andExpect(jsonPath("$.restMethods.length()").value(RestMethod.values().length))
                .andExpect(jsonPath("$.reflectionTimeFormat").value("HH:mm"))
                .andExpect(jsonPath("$.defaultTimeZone").value("Asia/Seoul"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(optionValues(response.get("genders"), "code"))
                .containsExactly("MALE", "FEMALE", "UNSPECIFIED");
        assertThat(optionValues(response.get("genders"), "label"))
                .containsExactly("남성", "여성", "선택하지 않음");
        assertThat(optionValues(response.get("interestAreas"), "code"))
                .containsExactly("STUDY", "CAREER", "WORK", "RELATIONSHIP", "FAMILY", "SELF", "HEALTH", "OTHER");
        assertThat(optionValues(response.get("interestAreas"), "label"))
                .containsExactly("학업", "취업·진로", "일·직장생활", "인간관계", "가족", "내 자신", "건강", "기타");
        assertThat(optionValues(response.get("restMethods"), "code"))
                .containsExactly(
                        "SLEEP", "MEDITATION", "ENJOYING_FOOD", "LISTENING_TO_MUSIC", "WATCHING_MOVIES_OR_DRAMAS",
                        "READING", "WRITING", "VISITING_A_CAFE", "WALKING", "TALKING_WITH_CLOSE_PEOPLE", "EXERCISE",
                        "GAMING", "VARIES_BY_DAY", "IDLE_REST", "OTHER"
                );
        assertThat(optionValues(response.get("restMethods"), "label"))
                .containsExactly(
                        "잠자기", "명상하기", "맛있는 음식 먹기", "음악 듣기", "영화, 드라마 보기", "독서하기", "글쓰기",
                        "카페가기", "산책하기", "가까운 사람과 대화하기", "운동하기", "게임하기", "그때마다 달라요", "멍하니 쉬기", "기타"
                );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder onboarding(User user, String body) {
        return put("/api/v1/users/me/onboarding")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String requestBody(
            String nickname,
            String age,
            String gender,
            String interestAreas,
            String reflectionTime,
            boolean calendarIntegrationEnabled
    ) {
        return """
                {"nickname":"%s","age":%s,"gender":"%s","interestAreas":%s,"reflectionTime":"%s","calendarIntegrationEnabled":%s}
                """.formatted(nickname, age, gender, interestAreas, reflectionTime, calendarIntegrationEnabled);
    }

    private UserProfile findProfileWithInterestAreas(Long userId) {
        return findProfileWithCollections(userId);
    }

    private UserProfile findProfileWithCollections(Long userId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.createQuery("""
                    select distinct userProfile
                    from UserProfile userProfile
                    left join fetch userProfile.interestAreas
                    left join fetch userProfile.restMethods
                    where userProfile.userId = :userId
                    """, UserProfile.class)
                    .setParameter("userId", userId)
                    .getSingleResult();
        } finally {
            entityManager.close();
        }
    }

    private List<String> optionValues(JsonNode options, String fieldName) {
        List<String> values = new ArrayList<>();
        for (JsonNode option : options) {
            values.add(option.get(fieldName).stringValue());
        }
        return values;
    }
}
