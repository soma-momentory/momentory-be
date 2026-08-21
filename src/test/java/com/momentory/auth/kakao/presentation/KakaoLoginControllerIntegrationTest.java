package com.momentory.auth.kakao.presentation;

import com.momentory.MomentoryApplication;
import com.momentory.auth.kakao.application.KakaoUserInfo;
import com.momentory.auth.kakao.infrastructure.KakaoApiClient;
import com.momentory.auth.kakao.infrastructure.KakaoApiErrorCode;
import com.momentory.auth.kakao.infrastructure.KakaoApiException;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = MomentoryApplication.class,
        properties = {
                "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
                "JWT_REFRESH_EXPIRATION=30d",
                "KAKAO_APP_ID=123456789"
        }
)
@Import(KakaoLoginControllerIntegrationTest.KakaoApiClientTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class KakaoLoginControllerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private KakaoApiClient kakaoApiClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        reset(kakaoApiClient);
        refreshTokenRepository.deleteAllInBatch();
        oauthAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void allowsUnauthenticatedKakaoLoginAndReturnsTokensForNewUser() throws Exception {
        givenKakaoUser("1001", "user@example.com");

        mockMvc.perform(loginRequest("kakao-native-access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(1800))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.onboardingRequired").value(true));
    }

    @Test
    void returnsOnboardingNotRequiredForCompletedUser() throws Exception {
        givenKakaoUser("1001", "user@example.com");
        MvcResult firstLogin = mockMvc.perform(loginRequest("first-token"))
                .andExpect(status().isOk())
                .andReturn();
        Long userId = responseBody(firstLogin).get("userId").longValue();
        User user = userRepository.findById(userId).orElseThrow();
        user.completeOnboarding();
        userRepository.saveAndFlush(user);

        mockMvc.perform(loginRequest("second-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false));
    }

    @Test
    void rejectsBlankAccessToken() throws Exception {
        mockMvc.perform(loginRequest("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsKakaoLoginWithoutEmailConsent() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.EMAIL_CONSENT_REQUIRED);

        mockMvc.perform(loginRequest("token-without-email-consent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KAKAO_EMAIL_CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.message").value("카카오계정 이메일 제공 동의가 필요합니다."));
    }

    @Test
    void rejectsKakaoLoginWithUnavailableEmail() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.EMAIL_UNAVAILABLE);

        mockMvc.perform(loginRequest("token-with-unavailable-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KAKAO_EMAIL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("유효하고 인증된 카카오계정 이메일이 필요합니다."));
    }

    @Test
    void mapsInvalidKakaoTokenToUnauthorized() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.INVALID_ACCESS_TOKEN);

        mockMvc.perform(loginRequest("invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_TOKEN_INVALID"));
    }

    @Test
    void mapsKakaoAppIdMismatchToUnauthorized() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.APP_ID_MISMATCH);

        mockMvc.perform(loginRequest("mismatched-app-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_APP_ID_MISMATCH"));
    }

    @Test
    void mapsKakaoServerErrorToBadGateway() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.KAKAO_API_SERVER_ERROR);

        mockMvc.perform(loginRequest("kakao-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("KAKAO_API_SERVER_ERROR"));
    }

    @Test
    void mapsKakaoNetworkErrorToServiceUnavailable() throws Exception {
        givenKakaoFailure(KakaoApiErrorCode.KAKAO_API_NETWORK_ERROR);

        mockMvc.perform(loginRequest("kakao-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KAKAO_API_NETWORK_ERROR"));
    }

    @Test
    void storesOnlyRefreshTokenHash() throws Exception {
        givenKakaoUser("1001", "user@example.com");

        MvcResult response = mockMvc.perform(loginRequest("kakao-token"))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = responseBody(response).get("refreshToken").stringValue();
        String storedHash = refreshTokenRepository.findAll().getFirst().getTokenHash();

        assertThat(storedHash).isNotEqualTo(refreshToken);
        assertThat(storedHash).isEqualTo(sha256(refreshToken));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String accessToken) {
        return post("/api/v1/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accessToken":"%s"}
                        """.formatted(accessToken));
    }

    private void givenKakaoUser(String providerUserId, String email) {
        when(kakaoApiClient.getUserInfo(anyString()))
                .thenReturn(new KakaoUserInfo(providerUserId, email));
    }

    private void givenKakaoFailure(KakaoApiErrorCode errorCode) {
        when(kakaoApiClient.getUserInfo(anyString()))
                .thenThrow(new KakaoApiException(errorCode, "internal Kakao failure"));
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KakaoApiClientTestConfiguration {

        @Bean
        @Primary
        KakaoApiClient mockKakaoApiClient() {
            return org.mockito.Mockito.mock(KakaoApiClient.class);
        }
    }
}
