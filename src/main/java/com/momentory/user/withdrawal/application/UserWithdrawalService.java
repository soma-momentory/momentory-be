package com.momentory.user.withdrawal.application;

import com.momentory.auth.apple.infrastructure.AppleRevokeClient;
import com.momentory.auth.kakao.infrastructure.KakaoUnlinkClient;
import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import org.springframework.stereotype.Service;

@Service
public class UserWithdrawalService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final AppleRevokeClient appleRevokeClient;
    private final UserDeletionService userDeletionService;

    public UserWithdrawalService(
            OAuthAccountRepository oauthAccountRepository,
            KakaoUnlinkClient kakaoUnlinkClient,
            AppleRevokeClient appleRevokeClient,
            UserDeletionService userDeletionService
    ) {
        this.oauthAccountRepository = oauthAccountRepository;
        this.kakaoUnlinkClient = kakaoUnlinkClient;
        this.appleRevokeClient = appleRevokeClient;
        this.userDeletionService = userDeletionService;
    }

    /**
     * <b>공급자 연결을 먼저 끊고 계정을 지운다.</b> 순서가 규칙이다 — 계정을 먼저
     * 지우면 어느 토큰으로 끊어야 하는지도 함께 사라져 되돌릴 방법이 없다.
     *
     * <p>애플도 카카오와 같은 의무가 있다. App Store 심사 규정이 계정 삭제를 요구하고,
     * Sign in with Apple 로 가입한 사용자는 서버가 애플에 알려야 사용자 쪽 설정에서도
     * 연결이 사라진다({@link AppleRevokeClient}).
     */
    public void withdraw(Long userId) {
        oauthAccountRepository.findByUser_IdAndProvider(userId, OAuthProvider.KAKAO)
                .map(OAuthAccount::getProviderUserId)
                .ifPresent(kakaoUnlinkClient::unlink);

        oauthAccountRepository.findByUser_IdAndProvider(userId, OAuthProvider.APPLE)
                // map(getAppleRefreshToken)은 null을 Optional.empty로 바꿔 revoke 호출과
                // 그 안의 진단 로그까지 건너뛴다. 계정 행이 있으면 null도 명시적으로
                // 넘겨, 레거시 계정의 토큰 누락을 운영에서 식별할 수 있게 한다.
                .ifPresent(account -> appleRevokeClient.revoke(account.getAppleRefreshToken()));

        userDeletionService.delete(userId);
    }
}
