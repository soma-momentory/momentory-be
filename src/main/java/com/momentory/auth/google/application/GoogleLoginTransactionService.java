package com.momentory.auth.google.application;

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

/**
 * 검증된 구글 사용자에게 모멘토리 세션을 내준다.
 *
 * <p>애플의 {@code AppleLoginTransactionService} 에서 <b>애플 refresh token 을
 * 저장하는 절만 빠졌다.</b> 구글은 탈퇴 때 서버가 끊을 연결을 들고 있지 않다 —
 * 우리가 받는 것은 ID token 하나뿐이고 그것은 보관하지 않는다. 연결 해제는 앱이
 * {@code GoogleSignin.revokeAccess()} 로 한다(FE {@code auth.ts}).
 */
@Service
public class GoogleLoginTransactionService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final GoogleAccountRegistrationService registrationService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public GoogleLoginTransactionService(
            OAuthAccountRepository oauthAccountRepository,
            UserRepository userRepository,
            GoogleAccountRegistrationService registrationService,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.oauthAccountRepository = oauthAccountRepository;
        this.userRepository = userRepository;
        this.registrationService = registrationService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * <b>계정을 잇는 열쇠는 {@code sub} 다 — 이메일이 아니다.</b> 구글 계정의
     * 이메일은 바뀔 수 있지만 {@code sub} 는 바뀌지 않는다. 이메일로 이으면
     * 주소를 바꾼 사용자가 남의 계정에 앉거나 자기 기록을 잃는다.
     */
    @Transactional
    public GoogleLoginResult login(GoogleUserInfo googleUserInfo) {
        Long userId = findExistingUserId(googleUserInfo.providerUserId())
                .orElseGet(() -> createOrFindUserId(googleUserInfo));
        User user = userRepository.findById(userId)
                .orElseThrow(IllegalStateException::new);
        user.updateEmail(googleUserInfo.email());

        IssuedAccessToken accessToken = accessTokenIssuer.issue(user.getId(), user.getRole());
        IssuedRefreshToken refreshToken = refreshTokenIssuer.issue();
        refreshTokenRepository.save(RefreshToken.create(user, refreshToken.hash(), refreshToken.expiresAt()));

        return new GoogleLoginResult(
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresIn(),
                user.getId(),
                user.requiresOnboarding()
        );
    }

    private Optional<Long> findExistingUserId(String providerUserId) {
        return oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, providerUserId)
                .map(OAuthAccount::getUser)
                .map(User::getId);
    }

    /** 같은 사용자의 첫 로그인이 겹쳤을 때 진 쪽은 이긴 쪽의 계정을 찾아 쓴다 */
    private Long createOrFindUserId(GoogleUserInfo googleUserInfo) {
        try {
            return registrationService.createUserAndAccount(
                    googleUserInfo.providerUserId(),
                    googleUserInfo.email()
            );
        } catch (DataIntegrityViolationException exception) {
            return findExistingUserId(googleUserInfo.providerUserId())
                    .orElseThrow(() -> exception);
        }
    }
}
