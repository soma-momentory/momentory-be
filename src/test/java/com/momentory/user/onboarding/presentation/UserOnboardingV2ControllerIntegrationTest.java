package com.momentory.user.onboarding.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.OnboardingResponse;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.OnboardingResponseRepository;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.user.withdrawal.application.UserDeletionService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalTime;
import java.util.Set;

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
class UserOnboardingV2ControllerIntegrationTest {

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

    private static final String FULL_BODY = """
            {"nickname":" 하늘 ","age":25,"gender":"FEMALE","reflectionTime":"21:00",
             "notificationEnabled":true,"calendarIntegrationEnabled":false,
             "moodChange":"FEW_TIMES","knowWhy":"SOMETIMES","dayEnd":"TIRED_SLEEP","diaryHistory":"STOPPED",
             "goals":["KNOW_WHEN_TIRED","DIARY_HABIT"]}
            """;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired UserProfileRepository userProfileRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired UserDeletionService userDeletionService;
    @MockitoSpyBean OnboardingResponseRepository onboardingResponseRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        reset(onboardingResponseRepository);
        onboardingResponseRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAllInBatch();
    }

    @Test
    void newUserGetsProfileWithoutPreferencesAnswersAndLatestVersion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(onboarding(user, FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.onboardingRequired").value(false))
                .andExpect(jsonPath("$.onboardingVersion").value(2));

        UserProfile profile = findProfile(user.getId());
        assertThat(profile.getNickname()).isEqualTo("하늘");
        assertThat(profile.getAge()).isEqualTo(25);
        assertThat(profile.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(profile.getReflectionTime()).isEqualTo(LocalTime.of(21, 0));
        assertThat(profile.isNotificationEnabled()).isTrue();
        assertThat(profile.isCalendarIntegrationEnabled()).isFalse();
        assertThat(profile.getInterestAreas()).isEmpty();
        assertThat(profile.getRestMethods()).isEmpty();

        OnboardingResponse response = findResponse(user.getId());
        assertThat(response.getMoodChange()).isEqualTo(OnboardingResponse.MoodChange.FEW_TIMES);
        assertThat(response.getKnowWhy()).isEqualTo(OnboardingResponse.KnowWhy.SOMETIMES);
        assertThat(response.getDayEnd()).isEqualTo(OnboardingResponse.DayEnd.TIRED_SLEEP);
        assertThat(response.getDiaryHistory()).isEqualTo(OnboardingResponse.DiaryHistory.STOPPED);
        assertThat(response.getGoals()).containsExactlyInAnyOrder(
                OnboardingResponse.Goal.KNOW_WHEN_TIRED, OnboardingResponse.Goal.DIARY_HABIT);

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false))
                .andExpect(jsonPath("$.onboardingVersion").value(2))
                .andExpect(jsonPath("$.latestOnboardingVersion").value(2))
                .andExpect(jsonPath("$.profile.interestAreas").isEmpty());
    }

    @Test
    void reonboardingKeepsExistingPreferencesAndRaisesLegacyVersion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(put("/api/v1/users/me/onboarding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"모리","age":30,"gender":"MALE",
                                 "interestAreas":["CAREER","OTHER"],"otherInterestDetail":"에세이 글쓰기",
                                 "restMethods":["READING","OTHER"],"otherRestMethodDetail":"따뜻한 차 마시기",
                                 "reflectionTime":"22:30","calendarIntegrationEnabled":true,"notificationEnabled":false}
                                """))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardingVersion()).isEqualTo(1);

        mockMvc.perform(onboarding(user, FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingVersion").value(2));

        UserProfile profile = findProfile(user.getId());
        assertThat(profile.getNickname()).isEqualTo("하늘");
        assertThat(profile.getAge()).isEqualTo(25);
        assertThat(profile.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(profile.isNotificationEnabled()).isTrue();
        assertThat(profile.isCalendarIntegrationEnabled()).isFalse();
        assertThat(profile.getInterestAreas()).containsExactlyInAnyOrder(InterestArea.CAREER, InterestArea.OTHER);
        assertThat(profile.getOtherInterestDetail()).isEqualTo("에세이 글쓰기");
        assertThat(profile.getRestMethods()).containsExactlyInAnyOrder(RestMethod.READING, RestMethod.OTHER);
        assertThat(profile.getOtherRestMethodDetail()).isEqualTo("따뜻한 차 마시기");
    }

    @Test
    void legacySaveAfterLatestOnboardingKeepsLatestVersion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(onboarding(user, FULL_BODY)).andExpect(status().isOk());

        // 마이페이지 저장은 v1 을 쓴다. 새 가입자는 관심사가 비어 있다.
        mockMvc.perform(put("/api/v1/users/me/onboarding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"하늘","age":25,"gender":"FEMALE","interestAreas":[],
                                 "reflectionTime":"21:00","calendarIntegrationEnabled":false}
                                """))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardingVersion()).isEqualTo(2);
    }

    @Test
    void repeatedRequestOverwritesSameVersionAnswers() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(onboarding(user, FULL_BODY)).andExpect(status().isOk());

        mockMvc.perform(onboarding(user, """
                {"nickname":"하늘","age":null,"gender":"UNSPECIFIED","reflectionTime":"22:30",
                 "notificationEnabled":false,"calendarIntegrationEnabled":false,
                 "moodChange":null,"dayEnd":"OKAY","goals":[]}
                """))
                .andExpect(status().isOk());

        assertThat(onboardingResponseRepository.count()).isEqualTo(1);
        assertThat(userProfileRepository.count()).isEqualTo(1);
        OnboardingResponse response = findResponse(user.getId());
        assertThat(response.getMoodChange()).isNull();
        assertThat(response.getKnowWhy()).isNull();
        assertThat(response.getDayEnd()).isEqualTo(OnboardingResponse.DayEnd.OKAY);
        assertThat(response.getDiaryHistory()).isNull();
        assertThat(response.getGoals()).isEmpty();
        UserProfile profile = findProfile(user.getId());
        assertThat(profile.getAge()).isNull();
        assertThat(profile.getGender()).isEqualTo(Gender.UNSPECIFIED);
    }

    @Test
    void rollsBackProfileAndCompletionWhenAnswerStorageFails() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        doThrow(new DataIntegrityViolationException("simulated answer save failure"))
                .when(onboardingResponseRepository).save(any(OnboardingResponse.class));

        assertThatThrownBy(() -> mockMvc.perform(onboarding(user, FULL_BODY)))
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        reset(onboardingResponseRepository);
        User persisted = userRepository.findById(user.getId()).orElseThrow();
        assertThat(persisted.requiresOnboarding()).isTrue();
        assertThat(persisted.getOnboardingVersion()).isZero();
        assertThat(userProfileRepository.findById(user.getId())).isEmpty();
        assertThat(onboardingResponseRepository.count()).isZero();
    }

    @Test
    void withdrawalDeletesAnswersAndGoals() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(onboarding(user, FULL_BODY)).andExpect(status().isOk());

        userDeletionService.delete(user.getId());

        assertThat(onboardingResponseRepository.count()).isZero();
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            Number goals = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM user_onboarding_goals")
                    .getSingleResult();
            assertThat(goals.longValue()).isZero();
        } finally {
            entityManager.close();
        }
    }

    @Test
    void rejectsInvalidRequestsAndMissingToken() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(put("/api/v2/users/me/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(onboarding(user, FULL_BODY.replace("\"notificationEnabled\":true,", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("notificationEnabled는 필수입니다."));
        mockMvc.perform(onboarding(user, FULL_BODY.replace("[\"KNOW_WHEN_TIRED\",\"DIARY_HABIT\"]", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("goals는 필수입니다."));
        mockMvc.perform(onboarding(user, FULL_BODY.replace("\"FEW_TIMES\"", "\"UNKNOWN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(onboarding(user, FULL_BODY.replace("\"gender\":\"FEMALE\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("gender는 필수입니다."));
        mockMvc.perform(onboarding(user, FULL_BODY.replace("\"21:00\"", "\"9:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().requiresOnboarding()).isTrue();
        assertThat(userProfileRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder onboarding(User user, String body) {
        return put("/api/v2/users/me/onboarding")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }

    private UserProfile findProfile(Long userId) {
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

    private OnboardingResponse findResponse(Long userId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.createQuery("""
                    select distinct response
                    from OnboardingResponse response
                    left join fetch response.goals
                    where response.id = :id
                    """, OnboardingResponse.class)
                    .setParameter("id", new OnboardingResponse.Id(userId, User.LATEST_ONBOARDING_VERSION))
                    .getSingleResult();
        } finally {
            entityManager.close();
        }
    }
}
