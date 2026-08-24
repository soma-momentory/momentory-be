package com.momentory.auth.apple.presentation;

import com.momentory.MomentoryApplication;
import com.momentory.auth.apple.application.AppleUserInfo;
import com.momentory.auth.apple.infrastructure.AppleApiErrorCode;
import com.momentory.auth.apple.infrastructure.AppleApiException;
import com.momentory.auth.apple.infrastructure.AppleIdentityTokenVerifier;
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
@Import(AppleLoginControllerIntegrationTest.AppleIdentityTokenVerifierTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AppleLoginControllerIntegrationTest {

    /** 요청 본문에 실리는 nonce 원문 — 이 테스트는 verifier 를 목으로 둔다 */
    private static final String NONCE = "3f1a9c00deadbeef";

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
    private AppleIdentityTokenVerifier identityTokenVerifier;

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
        reset(identityTokenVerifier);
        refreshTokenRepository.deleteAllInBatch();
        oauthAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void allowsUnauthenticatedAppleLoginAndReturnsTokensForNewUser() throws Exception {
        givenAppleUser("001234.abc", "user@example.com");

        mockMvc.perform(loginRequest("apple-identity-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(1800))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.onboardingRequired").value(true));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.APPLE, "001234.abc"))
                .isPresent();
    }

    @Test
    void returnsOnboardingNotRequiredForCompletedUser() throws Exception {
        givenAppleUser("001234.abc", "user@example.com");
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
    void rejectsBlankNonce() throws Exception {
        mockMvc.perform(loginRequest("apple-identity-token", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("nonce는 필수입니다."));
    }

    @Test
    void rejectsNonceMismatch() throws Exception {
        givenAppleFailure(AppleApiErrorCode.NONCE_MISMATCH);

        mockMvc.perform(loginRequest("replayed-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPLE_NONCE_MISMATCH"))
                .andExpect(jsonPath("$.message").value("애플 인증에 실패했습니다."));
    }

    @Test
    void passesRequestNonceToVerifier() throws Exception {
        givenAppleUser("001234.abcdef.0000", "user@example.com");

        mockMvc.perform(loginRequest("apple-identity-token", "client-nonce"))
                .andExpect(status().isOk());

        // 요청 본문의 nonce 가 그대로 검증기로 간다 — 여기서 바뀌면 항상 불일치다
        verify(identityTokenVerifier).verify("apple-identity-token", "client-nonce");
    }

    @Test
    void rejectsBlankIdentityToken() throws Exception {
        mockMvc.perform(loginRequest("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("identityToken은 필수입니다."));
    }

    @Test
    void rejectsAppleLoginWithUnavailableEmail() throws Exception {
        givenAppleFailure(AppleApiErrorCode.EMAIL_UNAVAILABLE);

        mockMvc.perform(loginRequest("token-without-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APPLE_EMAIL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("유효하고 인증된 애플계정 이메일이 필요합니다."));
    }

    @Test
    void mapsInvalidIdentityTokenToUnauthorized() throws Exception {
        givenAppleFailure(AppleApiErrorCode.INVALID_IDENTITY_TOKEN);

        mockMvc.perform(loginRequest("invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPLE_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").value("애플 인증에 실패했습니다."));
    }

    @Test
    void mapsClientIdMismatchToUnauthorized() throws Exception {
        givenAppleFailure(AppleApiErrorCode.CLIENT_ID_MISMATCH);

        mockMvc.perform(loginRequest("mismatched-client-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPLE_CLIENT_ID_MISMATCH"));
    }

    @Test
    void mapsAppleServerErrorToBadGateway() throws Exception {
        givenAppleFailure(AppleApiErrorCode.APPLE_API_SERVER_ERROR);

        mockMvc.perform(loginRequest("apple-identity-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("APPLE_API_SERVER_ERROR"));
    }

    @Test
    void mapsUnexpectedAppleResponseToBadGateway() throws Exception {
        givenAppleFailure(AppleApiErrorCode.UNEXPECTED_APPLE_RESPONSE);

        mockMvc.perform(loginRequest("apple-identity-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("APPLE_API_RESPONSE_ERROR"));
    }

    @Test
    void mapsAppleNetworkErrorToServiceUnavailable() throws Exception {
        givenAppleFailure(AppleApiErrorCode.APPLE_API_NETWORK_ERROR);

        mockMvc.perform(loginRequest("apple-identity-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPLE_API_NETWORK_ERROR"));
    }

    @Test
    void storesOnlyRefreshTokenHash() throws Exception {
        givenAppleUser("001234.abc", "user@example.com");

        MvcResult response = mockMvc.perform(loginRequest("apple-identity-token"))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = responseBody(response).get("refreshToken").stringValue();
        String storedHash = refreshTokenRepository.findAll().getFirst().getTokenHash();

        assertThat(storedHash).isNotEqualTo(refreshToken);
        assertThat(storedHash).isEqualTo(sha256(refreshToken));
    }

    private MockHttpServletRequestBuilder loginRequest(String identityToken) {
        return loginRequest(identityToken, NONCE);
    }

    private MockHttpServletRequestBuilder loginRequest(String identityToken, String nonce) {
        return post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identityToken":"%s","nonce":"%s"}
                        """.formatted(identityToken, nonce));
    }

    private void givenAppleUser(String providerUserId, String email) {
        when(identityTokenVerifier.verify(anyString(), anyString()))
                .thenReturn(new AppleUserInfo(providerUserId, email));
    }

    private void givenAppleFailure(AppleApiErrorCode errorCode) {
        when(identityTokenVerifier.verify(anyString(), anyString()))
                .thenThrow(new AppleApiException(errorCode, "internal Apple failure"));
    }

    private JsonNode responseBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AppleIdentityTokenVerifierTestConfiguration {

        @Bean
        @Primary
        AppleIdentityTokenVerifier mockAppleIdentityTokenVerifier() {
            return org.mockito.Mockito.mock(AppleIdentityTokenVerifier.class);
        }
    }
}
