package com.momentory.auth.apple.application;

import com.momentory.auth.apple.infrastructure.AppleTokenClient;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.auth.token.application.IssuedAccessToken;
import com.momentory.auth.token.application.IssuedRefreshToken;
import com.momentory.auth.token.application.RefreshTokenIssuer;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AppleLoginTransactionService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final AppleAccountRegistrationService registrationService;
    private final AppleTokenClient tokenClient;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public AppleLoginTransactionService(
            OAuthAccountRepository oauthAccountRepository,
            UserRepository userRepository,
            AppleAccountRegistrationService registrationService,
            AppleTokenClient tokenClient,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.oauthAccountRepository = oauthAccountRepository;
        this.userRepository = userRepository;
        this.registrationService = registrationService;
        this.tokenClient = tokenClient;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * @param authorizationCode 탈퇴 시 연결 해제에 쓸 refresh token 으로 교환한다.
     *                          <b>교환에 실패해도 로그인은 계속된다</b> — 지금 로그인하려는
     *                          사용자와 무관한 준비 작업이다({@link AppleTokenClient}).
     */
    @Transactional
    public AppleLoginResult login(AppleUserInfo appleUserInfo, String authorizationCode) {
        Long userId = findExistingUserId(appleUserInfo.providerUserId())
                .orElseGet(() -> createOrFindUserId(appleUserInfo));
        User user = userRepository.findById(userId)
                .orElseThrow(IllegalStateException::new);
        user.updateEmail(appleUserInfo.email());

        storeAppleRefreshToken(appleUserInfo.providerUserId(), authorizationCode);

        IssuedAccessToken accessToken = accessTokenIssuer.issue(user.getId(), user.getRole());
        IssuedRefreshToken refreshToken = refreshTokenIssuer.issue();
        refreshTokenRepository.save(RefreshToken.create(user, refreshToken.hash(), refreshToken.expiresAt()));

        return new AppleLoginResult(
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresIn(),
                user.getId(),
                user.requiresOnboarding()
        );
    }

    /**
     * 애플이 준 code 를 refresh token 으로 바꿔 계정 행에 눕힌다.
     *
     * <p><b>실패는 조용하다.</b> 교환이 안 되면 {@code null} 이 오고, 그러면 값을 덮지
     * 않는다({@code updateAppleRefreshToken}) — 낡은 토큰이라도 없는 것보다 낫다.
     * 이 사용자는 다음 로그인에서 다시 채울 기회를 얻는다.
     */
    private void storeAppleRefreshToken(String providerUserId, String authorizationCode) {
        String refreshToken = tokenClient.exchangeRefreshToken(authorizationCode);
        if (refreshToken == null) {
            return;
        }
        oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.APPLE, providerUserId)
                .ifPresent(account -> account.updateAppleRefreshToken(refreshToken));
    }

    private Optional<Long> findExistingUserId(String providerUserId) {
        return oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.APPLE, providerUserId)
                .map(OAuthAccount::getUser)
                .map(User::getId);
    }

    private Long createOrFindUserId(AppleUserInfo appleUserInfo) {
        try {
            return registrationService.createUserAndAccount(
                    appleUserInfo.providerUserId(),
                    appleUserInfo.email()
            );
        } catch (DataIntegrityViolationException exception) {
            return findExistingUserId(appleUserInfo.providerUserId())
                    .orElseThrow(() -> exception);
        }
    }
}
