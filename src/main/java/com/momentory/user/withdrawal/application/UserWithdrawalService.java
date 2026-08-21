package com.momentory.user.withdrawal.application;

import com.momentory.auth.kakao.infrastructure.KakaoUnlinkClient;
import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import org.springframework.stereotype.Service;

@Service
public class UserWithdrawalService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final UserDeletionService userDeletionService;

    public UserWithdrawalService(
            OAuthAccountRepository oauthAccountRepository,
            KakaoUnlinkClient kakaoUnlinkClient,
            UserDeletionService userDeletionService
    ) {
        this.oauthAccountRepository = oauthAccountRepository;
        this.kakaoUnlinkClient = kakaoUnlinkClient;
        this.userDeletionService = userDeletionService;
    }

    public void withdraw(Long userId) {
        oauthAccountRepository.findByUser_IdAndProvider(userId, OAuthProvider.KAKAO)
                .map(OAuthAccount::getProviderUserId)
                .ifPresent(kakaoUnlinkClient::unlink);
        userDeletionService.delete(userId);
    }
}
