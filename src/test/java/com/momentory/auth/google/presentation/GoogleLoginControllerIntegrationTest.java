package com.momentory.auth.google.presentation;

import com.momentory.MomentoryApplication;
import com.momentory.auth.google.application.GoogleUserInfo;
import com.momentory.auth.google.infrastructure.GoogleApiErrorCode;
import com.momentory.auth.google.infrastructure.GoogleApiException;
import com.momentory.auth.google.infrastructure.GoogleIdTokenVerifier;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.OAuthProvider;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.mockito.Mockito.verify;
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
@Import(GoogleLoginControllerIntegrationTest.GoogleIdTokenVerifierTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class GoogleLoginControllerIntegrationTest {

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
    private GoogleIdTokenVerifier idTokenVerifier;

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
        reset(idTokenVerifier);
        refreshTokenRepository.deleteAllInBatch();
        oauthAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void allowsUnauthenticatedGoogleLoginAndReturnsTokensForNewUser() throws Exception {
        givenGoogleUser("109876543210987654321", "user@example.com");

        mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(1800))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.onboardingRequired").value(true));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.GOOGLE, "109876543210987654321")).isPresent();
    }

    @Test
    void reusesExistingUserOnSecondLogin() throws Exception {
        givenGoogleUser("109876543210987654321", "user@example.com");
        MvcResult firstLogin = mockMvc.perform(loginRequest("first-token"))
                .andExpect(status().isOk())
                .andReturn();
        Long firstUserId = responseBody(firstLogin).get("userId").longValue();

        MvcResult secondLogin = mockMvc.perform(loginRequest("second-token"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(responseBody(secondLogin).get("userId").longValue()).isEqualTo(firstUserId);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsOnboardingNotRequiredForCompletedUser() throws Exception {
        givenGoogleUser("109876543210987654321", "user@example.com");
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
    void passesRequestIdTokenToVerifier() throws Exception {
        givenGoogleUser("109876543210987654321", "user@example.com");

        mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isOk());

        verify(idTokenVerifier).verify("google-id-token");
    }

    @Test
    void rejectsBlankIdToken() throws Exception {
        mockMvc.perform(loginRequest("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("idToken은 필수입니다."));
    }

    @Test
    void rejectsGoogleLoginWithUnavailableEmail() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.EMAIL_UNAVAILABLE);

        mockMvc.perform(loginRequest("token-without-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_EMAIL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("유효하고 인증된 구글계정 이메일이 필요합니다."));
    }

    @Test
    void mapsInvalidIdTokenToUnauthorized() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.INVALID_ID_TOKEN);

        mockMvc.perform(loginRequest("invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GOOGLE_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").value("구글 인증에 실패했습니다."));
    }

    @Test
    void mapsClientIdMismatchToUnauthorized() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.CLIENT_ID_MISMATCH);

        mockMvc.perform(loginRequest("mismatched-client-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GOOGLE_CLIENT_ID_MISMATCH"));
    }

    @Test
    void mapsGoogleServerErrorToBadGateway() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.GOOGLE_API_SERVER_ERROR);

        mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GOOGLE_API_SERVER_ERROR"));
    }

    @Test
    void mapsUnexpectedGoogleResponseToBadGateway() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.UNEXPECTED_GOOGLE_RESPONSE);

        mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GOOGLE_API_RESPONSE_ERROR"));
    }

    @Test
    void mapsGoogleNetworkErrorToServiceUnavailable() throws Exception {
        givenGoogleFailure(GoogleApiErrorCode.GOOGLE_API_NETWORK_ERROR);

        mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GOOGLE_API_NETWORK_ERROR"));
    }

    @Test
    void storesOnlyRefreshTokenHash() throws Exception {
        givenGoogleUser("109876543210987654321", "user@example.com");

        MvcResult response = mockMvc.perform(loginRequest("google-id-token"))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = responseBody(response).get("refreshToken").stringValue();
        String storedHash = refreshTokenRepository.findAll().getFirst().getTokenHash();

        assertThat(storedHash).isNotEqualTo(refreshToken);
        assertThat(storedHash).isEqualTo(sha256(refreshToken));
    }

    private MockHttpServletRequestBuilder loginRequest(String idToken) {
        return post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"%s"}
                        """.formatted(idToken));
    }

    private void givenGoogleUser(String providerUserId, String email) {
        when(idTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleUserInfo(providerUserId, email));
    }

    private void givenGoogleFailure(GoogleApiErrorCode errorCode) {
        when(idTokenVerifier.verify(anyString()))
                .thenThrow(new GoogleApiException(errorCode, "internal Google failure"));
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoogleIdTokenVerifierTestConfiguration {

        @Bean
        @Primary
        GoogleIdTokenVerifier mockGoogleIdTokenVerifier() {
            return org.mockito.Mockito.mock(GoogleIdTokenVerifier.class);
        }
    }
}
