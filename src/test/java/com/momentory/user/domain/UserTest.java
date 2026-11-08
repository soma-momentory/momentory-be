package com.momentory.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void newUserRequiresOnboardingByDefault() {
        User user = User.create();

        assertThat(user.requiresOnboarding()).isTrue();
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void completeOnboardingMakesOnboardingNotRequired() {
        User user = User.create();

        user.completeOnboarding();

        assertThat(user.requiresOnboarding()).isFalse();
        assertThat(user.getOnboardingVersion()).isEqualTo(1);
    }

    @Test
    void latestOnboardingRaisesVersionAndLegacyOnboardingNeverLowersIt() {
        User user = User.create();
        assertThat(user.getOnboardingVersion()).isZero();

        user.completeLatestOnboarding();
        user.completeOnboarding();

        assertThat(user.requiresOnboarding()).isFalse();
        assertThat(user.getOnboardingVersion()).isEqualTo(User.LATEST_ONBOARDING_VERSION);
    }

    @Test
    void createsAndUpdatesKakaoEmail() {
        User user = User.create(" first@example.com ");

        assertThat(user.getEmail()).isEqualTo("first@example.com");

        user.updateEmail("second@example.com");

        assertThat(user.getEmail()).isEqualTo("second@example.com");
    }

    @Test
    void rejectsMissingEmailForKakaoUser() {
        assertThatThrownBy(() -> User.create("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create(null))
                .isInstanceOf(NullPointerException.class);
    }
}
