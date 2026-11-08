package com.momentory.user.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import com.momentory.common.time.TimeZonePolicy;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "user_profiles")
public class UserProfile extends BaseTimeEntity {

    public static final int NICKNAME_MAX_LENGTH = 10;
    public static final int OTHER_DETAIL_MAX_LENGTH = 50;
    public static final String DEFAULT_TIME_ZONE = TimeZonePolicy.DEFAULT_TIME_ZONE_ID;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_profiles_user")
    )
    private User user;

    @Column(nullable = false, length = NICKNAME_MAX_LENGTH)
    private String nickname;

    private Integer age;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Gender gender;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_interest_areas",
            joinColumns = @JoinColumn(
                    name = "user_id",
                    nullable = false,
                    foreignKey = @ForeignKey(name = "fk_user_interest_areas_user")
            )
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "interest_area", nullable = false, length = 30)
    private Set<InterestArea> interestAreas = new HashSet<>();

    @Column(name = "other_interest_detail", length = OTHER_DETAIL_MAX_LENGTH)
    private String otherInterestDetail;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_rest_methods",
            joinColumns = @JoinColumn(
                    name = "user_id",
                    nullable = false,
                    foreignKey = @ForeignKey(name = "fk_user_rest_methods_user")
            )
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "rest_method", nullable = false, length = 50)
    private Set<RestMethod> restMethods = new HashSet<>();

    @Column(name = "other_rest_method_detail", length = OTHER_DETAIL_MAX_LENGTH)
    private String otherRestMethodDetail;

    @Column(name = "reflection_time", nullable = false)
    private LocalTime reflectionTime;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "calendar_integration_enabled", nullable = false)
    private boolean calendarIntegrationEnabled;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    /** 바바의 편지 알림 시각 — null 은 아직 고르지 않았다는 뜻이다(앱은 08:30 으로 보여 준다). */
    @Column(name = "letter_time")
    private LocalTime letterTime;

    @Column(name = "letter_notification_enabled", nullable = false)
    private boolean letterNotificationEnabled = true;

    protected UserProfile() {
    }

    private UserProfile(
            User user,
            String nickname,
            Integer age,
            Gender gender,
            Set<InterestArea> interestAreas,
            String otherInterestDetail,
            Set<RestMethod> restMethods,
            String otherRestMethodDetail,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            Boolean notificationEnabled
    ) {
        this.user = Objects.requireNonNull(user);
        update(
                nickname,
                age,
                gender,
                interestAreas,
                otherInterestDetail,
                restMethods,
                otherRestMethodDetail,
                reflectionTime,
                calendarIntegrationEnabled,
                notificationEnabled
        );
    }

    public static UserProfile create(
            User user,
            String nickname,
            Integer age,
            Gender gender,
            Set<InterestArea> interestAreas,
            String otherInterestDetail,
            Set<RestMethod> restMethods,
            String otherRestMethodDetail,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            Boolean notificationEnabled
    ) {
        return new UserProfile(
                user,
                nickname,
                age,
                gender,
                interestAreas,
                otherInterestDetail,
                restMethods,
                otherRestMethodDetail,
                reflectionTime,
                calendarIntegrationEnabled,
                notificationEnabled
        );
    }

    public void update(
            String nickname,
            Integer age,
            Gender gender,
            Set<InterestArea> interestAreas,
            String otherInterestDetail,
            Set<RestMethod> restMethods,
            String otherRestMethodDetail,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            Boolean notificationEnabled
    ) {
        updateBasicInfo(nickname, age, gender, reflectionTime, calendarIntegrationEnabled, notificationEnabled);
        replaceInterestAreas(interestAreas);
        this.otherInterestDetail = requireOtherDetail(interestAreas.contains(InterestArea.OTHER), otherInterestDetail, "interest area");
        updateRestMethods(restMethods, otherRestMethodDetail);
    }

    /**
     * 새 온보딩이 묻는 항목만 고친다. 관심사 · 쉬는 방법은 새 온보딩이 묻지 않으므로
     * 다시 온보딩하는 기존 사용자의 값을 그대로 둔다.
     */
    public void updateBasicInfo(
            String nickname,
            Integer age,
            Gender gender,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            Boolean notificationEnabled
    ) {
        this.nickname = requireNickname(nickname);
        this.age = requireValidAge(age);
        this.gender = Objects.requireNonNull(gender);
        this.reflectionTime = Objects.requireNonNull(reflectionTime);
        this.timeZone = DEFAULT_TIME_ZONE;
        this.calendarIntegrationEnabled = calendarIntegrationEnabled;
        if (notificationEnabled != null) {
            this.notificationEnabled = notificationEnabled;
        }
    }

    private String requireNickname(String nickname) {
        String normalized = nickname == null ? null : nickname.strip();
        if (normalized == null || normalized.isBlank() || normalized.length() > NICKNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Nickname must be between 1 and 10 characters.");
        }
        return normalized;
    }

    private Integer requireValidAge(Integer age) {
        if (age != null && (age < 1 || age > 120)) {
            throw new IllegalArgumentException("Age must be between 1 and 120.");
        }
        return age;
    }

    // 새 온보딩은 관심사를 묻지 않아 새 가입자는 비어 있다. 비어 있어도 마이페이지 저장이 되게 한다.
    private void replaceInterestAreas(Set<InterestArea> interestAreas) {
        Objects.requireNonNull(interestAreas);
        this.interestAreas.clear();
        this.interestAreas.addAll(interestAreas);
    }

    private void updateRestMethods(Set<RestMethod> restMethods, String otherRestMethodDetail) {
        if (restMethods == null) {
            if (otherRestMethodDetail != null) {
                throw new IllegalArgumentException("Other rest method detail requires OTHER.");
            }
            return;
        }
        if (restMethods.isEmpty()) {
            throw new IllegalArgumentException("At least one rest method is required when provided.");
        }
        this.restMethods.clear();
        this.restMethods.addAll(restMethods);
        this.otherRestMethodDetail = requireOtherDetail(restMethods.contains(RestMethod.OTHER), otherRestMethodDetail, "rest method");
    }

    private String requireOtherDetail(boolean otherSelected, String detail, String type) {
        String normalized = detail == null ? null : detail.strip();
        if (otherSelected) {
            if (normalized == null || normalized.isBlank() || normalized.length() > OTHER_DETAIL_MAX_LENGTH) {
                throw new IllegalArgumentException("Other " + type + " detail must be between 1 and 50 characters.");
            }
            return normalized;
        }
        if (normalized != null) {
            throw new IllegalArgumentException("Other " + type + " detail is only allowed with OTHER.");
        }
        return null;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public Integer getAge() {
        return age;
    }

    public Gender getGender() {
        return gender;
    }

    public Set<InterestArea> getInterestAreas() {
        return Set.copyOf(interestAreas);
    }

    public String getOtherInterestDetail() {
        return otherInterestDetail;
    }

    public Set<RestMethod> getRestMethods() {
        return Set.copyOf(restMethods);
    }

    public String getOtherRestMethodDetail() {
        return otherRestMethodDetail;
    }

    /** 처음 일기를 저장할 때 · 알림 설정에서 고른 편지 알림 시각과 켜기 여부. */
    public void changeLetterPreference(LocalTime letterTime, boolean letterNotificationEnabled) {
        this.letterTime = Objects.requireNonNull(letterTime);
        this.letterNotificationEnabled = letterNotificationEnabled;
    }

    public LocalTime getLetterTime() {
        return letterTime;
    }

    public boolean isLetterNotificationEnabled() {
        return letterNotificationEnabled;
    }

    public LocalTime getReflectionTime() {
        return reflectionTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public boolean isCalendarIntegrationEnabled() {
        return calendarIntegrationEnabled;
    }

    public boolean isNotificationEnabled() {
        return notificationEnabled;
    }
}
