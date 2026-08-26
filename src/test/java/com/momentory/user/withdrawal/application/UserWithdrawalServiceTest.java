package com.momentory.user.withdrawal.application;

import com.momentory.auth.apple.infrastructure.AppleApiErrorCode;
import com.momentory.auth.apple.infrastructure.AppleApiException;
import com.momentory.auth.apple.infrastructure.AppleRevokeClient;
import com.momentory.auth.kakao.infrastructure.KakaoUnlinkClient;
import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private KakaoUnlinkClient kakaoUnlinkClient;

    @Mock
    private AppleRevokeClient appleRevokeClient;

    @Mock
    private UserDeletionService userDeletionService;

    @InjectMocks
    private UserWithdrawalService service;

    @Test
    void passesMissingAppleRefreshTokenToRevokeBeforeDeletingUser() {
        OAuthAccount appleAccount = org.mockito.Mockito.mock(OAuthAccount.class);
        when(oauthAccountRepository.findByUser_IdAndProvider(USER_ID, OAuthProvider.KAKAO))
                .thenReturn(Optional.empty());
        when(oauthAccountRepository.findByUser_IdAndProvider(USER_ID, OAuthProvider.APPLE))
                .thenReturn(Optional.of(appleAccount));
        when(appleAccount.getAppleRefreshToken()).thenReturn(null);

        service.withdraw(USER_ID);

        InOrder order = inOrder(appleRevokeClient, userDeletionService);
        order.verify(appleRevokeClient).revoke(null);
        order.verify(userDeletionService).delete(USER_ID);
    }

    @Test
    void doesNotDeleteUserWhenAppleRevokeFails() {
        OAuthAccount appleAccount = org.mockito.Mockito.mock(OAuthAccount.class);
        when(oauthAccountRepository.findByUser_IdAndProvider(USER_ID, OAuthProvider.KAKAO))
                .thenReturn(Optional.empty());
        when(oauthAccountRepository.findByUser_IdAndProvider(USER_ID, OAuthProvider.APPLE))
                .thenReturn(Optional.of(appleAccount));
        when(appleAccount.getAppleRefreshToken()).thenReturn("stored-refresh-token");
        org.mockito.Mockito.doThrow(new AppleApiException(
                        AppleApiErrorCode.APPLE_API_NETWORK_ERROR,
                        "unreachable"
                ))
                .when(appleRevokeClient).revoke("stored-refresh-token");

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOf(AppleApiException.class);

        verify(userDeletionService, never()).delete(USER_ID);
    }
}
