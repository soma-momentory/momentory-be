package com.momentory.auth.apple.application;

import com.momentory.MomentoryApplication;
import com.momentory.auth.apple.infrastructure.AppleApiErrorCode;
import com.momentory.auth.apple.infrastructure.AppleApiException;
import com.momentory.auth.apple.infrastructure.AppleIdentityTokenVerifier;
import com.momentory.auth.apple.infrastructure.AppleTokenClient;
import com.momentory.auth.security.JwtProperties;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.OAuthAccount;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = MomentoryApplication.class,
        properties = {
                "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
                "JWT_REFRESH_EXPIRATION=30d",
                "KAKAO_APP_ID=123456789"
        }
)
@Import(AppleLoginServiceIntegrationTest.AppleIdentityTokenVerifierTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AppleLoginServiceIntegrationTest {

    /** 이번 인가의 nonce 원문. 이 테스트는 verifier 를 목으로 두므로 값 자체는 통과 재료다 */
    private static final String NONCE = "3f1a9c00deadbeef";

    /** 애플 authorization code — 이 테스트는 교환 클라이언트를 목으로 둔다 */
    private static final String AUTH_CODE = "apple-authorization-code";
    private static final String APPLE_REFRESH_TOKEN = "apple-refresh-token";

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
    private AppleLoginService appleLoginService;

    @Autowired
    private AppleIdentityTokenVerifier identityTokenVerifier;

    @MockitoBean
    private AppleTokenClient appleTokenClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void prepareAppleTokenExchange() {
        when(appleTokenClient.exchangeRefreshToken(AUTH_CODE)).thenReturn(APPLE_REFRESH_TOKEN);
    }

    @AfterEach
    void cleanUp() {
        reset(identityTokenVerifier, appleTokenClient);
        refreshTokenRepository.deleteAllInBatch();
        oauthAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void createsUserAndOAuthAccountForFirstLogin() {
        givenAppleUser("001234.abc", "user@example.com");

        AppleLoginResult result = appleLoginService.login("apple-identity-token", NONCE, AUTH_CODE);

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(result.userId()).orElseThrow().getEmail())
                .isEqualTo("user@example.com");
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.APPLE, "001234.abc"))
                .hasValueSatisfying(account -> {
                    assertThat(account.getUser().getId()).isEqualTo(result.userId());
                    assertThat(account.getAppleRefreshToken()).isEqualTo(APPLE_REFRESH_TOKEN);
                });
        assertThat(result.onboardingRequired()).isTrue();
    }

    @Test
    void reusesExistingUserForExistingOAuthAccount() {
        givenAppleUser("001234.abc", "user@example.com");
        AppleLoginResult firstLogin = appleLoginService.login("first-token", NONCE, AUTH_CODE);

        AppleLoginResult secondLogin = appleLoginService.login("second-token", NONCE, AUTH_CODE);

        assertThat(secondLogin.userId()).isEqualTo(firstLogin.userId());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oauthAccountRepository.count()).isEqualTo(1);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void doesNotReuseKakaoAccountWithSameProviderUserId() {
        User kakaoUser = userRepository.saveAndFlush(User.create("kakao@example.com"));
        oauthAccountRepository.saveAndFlush(
                OAuthAccount.create(kakaoUser, OAuthProvider.KAKAO, "001234.abc"));
        givenAppleUser("001234.abc", "apple@example.com");

        AppleLoginResult result = appleLoginService.login("apple-identity-token", NONCE, AUTH_CODE);

        assertThat(result.userId()).isNotEqualTo(kakaoUser.getId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(oauthAccountRepository.count()).isEqualTo(2);
    }

    @Test
    void returnsOnboardingNotRequiredForCompletedUser() {
        givenAppleUser("001234.abc", "user@example.com");
        Long userId = appleLoginService.login("first-token", NONCE, AUTH_CODE).userId();
        User user = userRepository.findById(userId).orElseThrow();
        user.completeOnboarding();
        userRepository.saveAndFlush(user);

        AppleLoginResult result = appleLoginService.login("second-token", NONCE, AUTH_CODE);

        assertThat(result.onboardingRequired()).isFalse();
    }

    @Test
    void updatesExistingUsersEmailFromLatestIdentityToken() {
        givenAppleUser("001234.abc", "old@example.com");
        Long userId = appleLoginService.login("first-token", NONCE, AUTH_CODE).userId();

        givenAppleUser("001234.abc", "new@example.com");
        appleLoginService.login("second-token", NONCE, AUTH_CODE);

        assertThat(userRepository.findById(userId).orElseThrow().getEmail())
                .isEqualTo("new@example.com");
    }

    @Test
    void doesNotMergeDifferentAppleAccountsWithSameEmail() {
        when(identityTokenVerifier.verify("first-token", NONCE))
                .thenReturn(new AppleUserInfo("001234.abc", "shared@example.com"));
        when(identityTokenVerifier.verify("second-token", NONCE))
                .thenReturn(new AppleUserInfo("005678.def", "shared@example.com"));

        AppleLoginResult firstLogin = appleLoginService.login("first-token", NONCE, AUTH_CODE);
        AppleLoginResult secondLogin = appleLoginService.login("second-token", NONCE, AUTH_CODE);

        assertThat(firstLogin.userId()).isNotEqualTo(secondLogin.userId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(oauthAccountRepository.count()).isEqualTo(2);
    }

    @Test
    void issuesAccessAndRefreshTokensAndStoresRefreshTokenHash() throws Exception {
        givenAppleUser("001234.abc", "user@example.com");
        Instant beforeLogin = Instant.now();

        AppleLoginResult result = appleLoginService.login("apple-identity-token", NONCE, AUTH_CODE);
        Instant afterLogin = Instant.now();
        RefreshToken refreshToken = refreshTokenRepository.findAll().getFirst();

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.accessTokenExpiresIn()).isEqualTo(jwtProperties.accessTokenExpiration());
        assertThat(refreshToken.getTokenHash()).isNotEqualTo(result.refreshToken());
        assertThat(refreshToken.getTokenHash()).isEqualTo(sha256(result.refreshToken()));
        assertThat(refreshToken.getExpiresAt()).isBetween(
                beforeLogin.plus(jwtProperties.refreshExpiration()),
                afterLogin.plus(jwtProperties.refreshExpiration())
        );
    }

    @Test
    void preventsDuplicateUserForConcurrentFirstLogins() throws Exception {
        CountDownLatch bothRequestsValidated = new CountDownLatch(2);
        when(identityTokenVerifier.verify(anyString(), anyString())).thenAnswer(invocation -> {
            bothRequestsValidated.countDown();
            if (!bothRequestsValidated.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent login test did not start both requests.");
            }
            return new AppleUserInfo("001234.abc", "user@example.com");
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<AppleLoginResult>> logins = executor.invokeAll(List.of(
                    () -> appleLoginService.login("first-token", NONCE, AUTH_CODE),
                    () -> appleLoginService.login("second-token", NONCE, AUTH_CODE)
            ));
            Long firstUserId = logins.getFirst().get(10, TimeUnit.SECONDS).userId();
            Long secondUserId = logins.get(1).get(10, TimeUnit.SECONDS).userId();

            assertThat(firstUserId).isEqualTo(secondUserId);
        }

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oauthAccountRepository.count()).isEqualTo(1);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void doesNotPersistUserOrRefreshTokenWhenIdentityTokenIsInvalid() {
        when(identityTokenVerifier.verify("invalid-token", NONCE)).thenThrow(new AppleApiException(
                AppleApiErrorCode.INVALID_IDENTITY_TOKEN,
                "Apple identity token is invalid."
        ));

        assertThatThrownBy(() -> appleLoginService.login("invalid-token", NONCE, AUTH_CODE))
                .isInstanceOf(AppleApiException.class);

        assertThat(userRepository.count()).isZero();
        assertThat(oauthAccountRepository.count()).isZero();
        assertThat(refreshTokenRepository.count()).isZero();
    }

    private void givenAppleUser(String providerUserId, String email) {
        when(identityTokenVerifier.verify(anyString(), anyString()))
                .thenReturn(new AppleUserInfo(providerUserId, email));
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
