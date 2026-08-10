package com.momentory.user.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserProfileTest {

    @Test
    void createsProfileWithServerDefaultTimeZone() {
        UserProfile profile = UserProfile.create(
                User.create(),
                " 모리 ",
                null,
                Gender.UNSPECIFIED,
                Set.of(InterestArea.SELF),
                null,
                null,
                null,
                LocalTime.of(21, 30),
                true,
                null
        );

        assertThat(profile.getNickname()).isEqualTo("모리");
        assertThat(profile.getAge()).isNull();
        assertThat(profile.getTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(profile.isCalendarIntegrationEnabled()).isTrue();
    }

    @Test
    void replacesAllProfileFieldsAndInterestAreas() {
        UserProfile profile = UserProfile.create(
                User.create(),
                "모리",
                25,
                Gender.FEMALE,
                Set.of(InterestArea.CAREER, InterestArea.SELF),
                null,
                null,
                null,
                LocalTime.of(21, 30),
                true,
                null
        );

        profile.update(
                "새모리",
                30,
                Gender.MALE,
                Set.of(InterestArea.HEALTH),
                null,
                null,
                null,
                LocalTime.of(9, 0),
                false,
                null
        );

        assertThat(profile.getNickname()).isEqualTo("새모리");
        assertThat(profile.getAge()).isEqualTo(30);
        assertThat(profile.getGender()).isEqualTo(Gender.MALE);
        assertThat(profile.getInterestAreas()).containsExactly(InterestArea.HEALTH);
        assertThat(profile.getReflectionTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(profile.isCalendarIntegrationEnabled()).isFalse();
    }

    @Test
    void storesOtherDetailsMultipleRestMethodsAndNotificationPreference() {
        UserProfile profile = UserProfile.create(
                User.create(),
                "모리",
                25,
                Gender.FEMALE,
                Set.of(InterestArea.OTHER),
                " 에세이 글쓰기 ",
                Set.of(RestMethod.READING, RestMethod.OTHER),
                " 따뜻한 차 마시기 ",
                LocalTime.of(21, 30),
                true,
                true
        );

        assertThat(profile.getOtherInterestDetail()).isEqualTo("에세이 글쓰기");
        assertThat(profile.getRestMethods()).containsExactlyInAnyOrder(RestMethod.READING, RestMethod.OTHER);
        assertThat(profile.getOtherRestMethodDetail()).isEqualTo("따뜻한 차 마시기");
        assertThat(profile.isNotificationEnabled()).isTrue();
    }

    @Test
    void preservesOptionalPreferencesWhenTheyAreNotProvidedOnUpdate() {
        UserProfile profile = UserProfile.create(
                User.create(),
                "모리",
                25,
                Gender.FEMALE,
                Set.of(InterestArea.SELF),
                null,
                Set.of(RestMethod.READING),
                null,
                LocalTime.of(21, 30),
                true,
                true
        );

        profile.update(
                "새모리",
                25,
                Gender.FEMALE,
                Set.of(InterestArea.SELF),
                null,
                null,
                null,
                LocalTime.of(21, 30),
                false,
                null
        );

        assertThat(profile.getRestMethods()).containsExactly(RestMethod.READING);
        assertThat(profile.isNotificationEnabled()).isTrue();
    }

    @Test
    void rejectsOtherDetailWithoutOtherSelection() {
        assertThatThrownBy(() -> UserProfile.create(
                User.create(),
                "모리",
                25,
                Gender.FEMALE,
                Set.of(InterestArea.SELF),
                "에세이 글쓰기",
                Set.of(RestMethod.READING),
                null,
                LocalTime.of(21, 30),
                true,
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
