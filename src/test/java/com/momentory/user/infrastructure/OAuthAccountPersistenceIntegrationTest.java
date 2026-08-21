package com.momentory.user.infrastructure;

import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OAuthAccountPersistenceIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Test
    void persistsNewUserWithOnboardingCompletedFalse() {
        User user = userRepository.saveAndFlush(User.create());

        assertThat(user.requiresOnboarding()).isTrue();
    }

    @Test
    void rejectsDuplicateProviderAndProviderUserId() {
        User firstUser = userRepository.saveAndFlush(User.create());
        OAuthAccount account = oauthAccountRepository.saveAndFlush(OAuthAccount.create(
                firstUser,
                OAuthProvider.KAKAO,
                "123456789"
        ));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.KAKAO,
                "123456789"
        )).contains(account);

        User secondUser = userRepository.saveAndFlush(User.create());

        assertThatThrownBy(() -> oauthAccountRepository.saveAndFlush(OAuthAccount.create(
                secondUser,
                OAuthProvider.KAKAO,
                "123456789"
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
