package com.momentory.auth.kakao.application;

import com.momentory.MomentoryApplication;
import com.momentory.auth.kakao.infrastructure.KakaoApiClient;
import com.momentory.auth.kakao.infrastructure.KakaoApiErrorCode;
import com.momentory.auth.kakao.infrastructure.KakaoApiException;
import com.momentory.auth.security.JwtProperties;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@Import(KakaoLoginServiceIntegrationTest.KakaoApiClientTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class KakaoLoginServiceIntegrationTest {

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
    private KakaoLoginService kakaoLoginService;

    @Autowired
    private KakaoApiClient kakaoApiClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @AfterEach
    void cleanUp() {
        reset(kakaoApiClient);
        refreshTokenRepository.deleteAllInBatch();
        oauthAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void createsUserAndOAuthAccountForFirstLogin() {
        givenKakaoUser("1001", "user@example.com");

        KakaoLoginResult result = kakaoLoginService.login("kakao-access-token");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(result.userId()).orElseThrow().getEmail())
                .isEqualTo("user@example.com");
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "1001"))
                .hasValueSatisfying(account -> {
                    assertThat(account.getUser().getId()).isEqualTo(result.userId());
                });
        assertThat(result.onboardingRequired()).isTrue();
    }

    @Test
    void reusesExistingUserForExistingOAuthAccount() {
        givenKakaoUser("1001", "user@example.com");
        KakaoLoginResult firstLogin = kakaoLoginService.login("first-token");

        KakaoLoginResult secondLogin = kakaoLoginService.login("second-token");

        assertThat(secondLogin.userId()).isEqualTo(firstLogin.userId());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oauthAccountRepository.count()).isEqualTo(1);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void returnsOnboardingNotRequiredForCompletedUser() {
        givenKakaoUser("1001", "user@example.com");
        Long userId = kakaoLoginService.login("first-token").userId();
        User user = userRepository.findById(userId).orElseThrow();
        user.completeOnboarding();
        userRepository.saveAndFlush(user);

        KakaoLoginResult result = kakaoLoginService.login("second-token");

        assertThat(result.onboardingRequired()).isFalse();
    }

    @Test
    void doesNotCreateUserWhenKakaoDoesNotProvideEmail() {
        givenKakaoUser("1001", null);

        assertThatThrownBy(() -> kakaoLoginService.login("kakao-access-token"))
                .isInstanceOf(RuntimeException.class);

        assertThat(userRepository.count()).isZero();
        assertThat(oauthAccountRepository.count()).isZero();
        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    void updatesExistingUsersEmailFromLatestKakaoResponse() {
        givenKakaoUser("1001", "old@example.com");
        Long userId = kakaoLoginService.login("first-token").userId();

        givenKakaoUser("1001", "new@example.com");
        kakaoLoginService.login("second-token");

        assertThat(userRepository.findById(userId).orElseThrow().getEmail())
                .isEqualTo("new@example.com");
    }

    @Test
    void doesNotMergeDifferentKakaoAccountsWithSameEmail() {
        when(kakaoApiClient.getUserInfo("first-token"))
                .thenReturn(new KakaoUserInfo("1001", "shared@example.com"));
        when(kakaoApiClient.getUserInfo("second-token"))
                .thenReturn(new KakaoUserInfo("2002", "shared@example.com"));

        KakaoLoginResult firstLogin = kakaoLoginService.login("first-token");
        KakaoLoginResult secondLogin = kakaoLoginService.login("second-token");

        assertThat(firstLogin.userId()).isNotEqualTo(secondLogin.userId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(oauthAccountRepository.count()).isEqualTo(2);
    }

    @Test
    void issuesAccessAndRefreshTokensAndStoresRefreshTokenHash() throws Exception {
        givenKakaoUser("1001", "user@example.com");
        Instant beforeLogin = Instant.now();

        KakaoLoginResult result = kakaoLoginService.login("kakao-access-token");
        Instant afterLogin = Instant.now();
        RefreshToken refreshToken = onlyRefreshToken();

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
        when(kakaoApiClient.getUserInfo(anyString())).thenAnswer(invocation -> {
            bothRequestsValidated.countDown();
            if (!bothRequestsValidated.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent login test did not start both requests.");
            }
            return new KakaoUserInfo("1001", "user@example.com");
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<KakaoLoginResult>> logins = executor.invokeAll(List.of(
                    () -> kakaoLoginService.login("first-token"),
                    () -> kakaoLoginService.login("second-token")
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
    void doesNotPersistUserOrRefreshTokenWhenKakaoValidationFails() {
        when(kakaoApiClient.getUserInfo("invalid-token")).thenThrow(new KakaoApiException(
                KakaoApiErrorCode.INVALID_ACCESS_TOKEN,
                "Kakao access token is invalid."
        ));

        assertThatThrownBy(() -> kakaoLoginService.login("invalid-token"))
                .isInstanceOf(KakaoApiException.class);

        assertThat(userRepository.count()).isZero();
        assertThat(oauthAccountRepository.count()).isZero();
        assertThat(refreshTokenRepository.count()).isZero();
    }

    private void givenKakaoUser(String providerUserId, String email) {
        when(kakaoApiClient.getUserInfo(anyString()))
                .thenReturn(new KakaoUserInfo(providerUserId, email));
    }

    private RefreshToken onlyRefreshToken() {
        return refreshTokenRepository.findAll().getFirst();
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
