package com.momentory.auth.security;

import java.time.LocalTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;

/**
 * <b>로컬 개발 전용</b> — {@code local} 프로파일에서만 뜬다.
 *
 * <p>{@link LocalDevSecurityConfiguration} 의 목 로그인은 '가장 먼저 있는 유저'를 재사용하는데,
 * 그 유저는 온보딩을 거치지 않아 {@link UserProfile} 이 없다. 그러면 회고의 '쉬는 행동 카드'가
 * 참조할 온보딩 쉬는 방법 선호도 비어 있어, 로컬에서 그 기능을 확인할 수 없다.
 *
 * <p>그래서 앱 시작 시 목 유저를 확보하고(없으면 생성), <b>프로필이 없을 때만</b> 샘플 프로필을
 * 하나 심는다({@code restMethods} 포함). 이미 프로필이 있으면(직접 온보딩한 데이터 등) 손대지 않는다.
 *
 * <p><b>테스트에서 안 돌게 하려고</b> {@link WebServerInitializedEvent} 에 건다 — 이 이벤트는 실제
 * 내장 웹서버가 뜰 때만(즉 {@code bootRun} 같은 실제 구동에서만) 발생한다. 이 프로젝트는
 * {@code spring.profiles.default=local} 이라 프로파일을 안 지정한 {@code @SpringBootTest} 도 local
 * 로 뜨는데, 그 테스트들은 MOCK 웹환경이라 웹서버를 안 띄워 이 이벤트가 오지 않는다. 그래서
 * {@code ApplicationRunner} 로 걸면 테스트 컨텍스트마다 목 유저가 심겨 통합 테스트가 깨지지만,
 * 이 이벤트로 걸면 실제 로컬 구동에서만 심긴다.
 */
@Component
@Profile("local")
public class LocalMockUserInitializer {

    private static final Logger log = LoggerFactory.getLogger(LocalMockUserInitializer.class);

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public LocalMockUserInitializer(UserRepository userRepository,
            UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @EventListener(WebServerInitializedEvent.class)
    @Transactional
    public void seedMockUserProfile() {
        // 목 로그인이 고르는 것과 같은 '첫 유저'를 확보한다(없으면 생성).
        User user = userRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> userRepository.save(User.create()));

        if (userProfileRepository.findById(user.getId()).isPresent()) {
            return; // 이미 프로필이 있으면 실제 데이터일 수 있어 건드리지 않는다.
        }

        UserProfile profile = UserProfile.create(
                user,
                "정민",
                27,
                Gender.UNSPECIFIED,
                Set.of(InterestArea.CAREER),
                null,
                // 쉬는 행동 카드에서 선호가 보이도록 결이 다른 몇 가지를 심는다.
                Set.of(RestMethod.SLEEP, RestMethod.WALKING, RestMethod.LISTENING_TO_MUSIC,
                        RestMethod.ENJOYING_FOOD),
                null,
                LocalTime.of(22, 0),
                false,
                true);
        user.completeOnboarding();
        userProfileRepository.save(profile);

        log.info("[local] 목 유저(id={}) 샘플 프로필 생성 — restMethods={}",
                user.getId(), profile.getRestMethods());
    }
}
