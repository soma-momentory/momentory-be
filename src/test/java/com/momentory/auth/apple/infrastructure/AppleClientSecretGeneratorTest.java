package com.momentory.auth.apple.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * client_secret 서명 시험 — <b>애플이 받아 줄 모양인가</b>가 요점이다.
 *
 * <p>실제 {@code .p8} 은 없으므로 테스트용 EC 키를 만들어 쓴다. 검증도 애플이 하는
 * 것과 같은 방식(공개키로 ES256 서명 확인)으로 되짚는다 — 우리가 서명한 것을 우리가
 * 다시 읽기만 하면 형식이 틀려도 통과하기 때문이다.
 */
class AppleClientSecretGeneratorTest {

    private static final String TEAM_ID = "TEAM123456";
    private static final String KEY_ID = "KEY7890123";
    private static final String CLIENT_ID = "kr.momentory.app";

    private KeyPair keyPair;
    private String pem;

    @BeforeEach
    void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void signsClientSecretAppleCanVerify() throws Exception {
        SignedJWT jwt = SignedJWT.parse(generator(pem).generate());

        // 애플이 하는 검증 — 공개키로 ES256 서명을 확인한다
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(KEY_ID);
    }

    @Test
    void fillsClaimsAppleRequires() throws Exception {
        SignedJWT jwt = SignedJWT.parse(generator(pem).generate());

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(TEAM_ID);
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
        // sub 는 번들 ID 다 — identity token 검증의 aud 와 같은 값
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(CLIENT_ID);
    }

    @Test
    void keepsLifetimeShort() throws Exception {
        SignedJWT jwt = SignedJWT.parse(generator(pem).generate());

        Date issuedAt = jwt.getJWTClaimsSet().getIssueTime();
        Date expiresAt = jwt.getJWTClaimsSet().getExpirationTime();

        // 애플은 6개월까지 허용하지만 길게 만들 이유가 없다
        assertThat(expiresAt).isAfter(issuedAt);
        assertThat(expiresAt.toInstant()).isBefore(issuedAt.toInstant().plus(Duration.ofMinutes(10)));
    }

    @Test
    void acceptsPemWithEscapedNewlines() {
        // 환경변수로 들어오면 개행이 \n 두 글자로 올 수 있다 — 여기서 막히면
        // 「설정은 했는데 탈퇴가 안 된다」가 되고 원인이 보이지 않는다
        String escaped = pem.replace("\n", "\n");

        assertThat(generator(escaped).generate()).isNotBlank();
    }

    @Test
    void refusesWhenNotConfigured() {
        assertThatThrownBy(() -> generator(null).generate())
                .isInstanceOfSatisfying(AppleApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AppleApiErrorCode.REVOKE_NOT_CONFIGURED));
    }

    @Test
    void refusesUnreadablePrivateKey() {
        assertThatThrownBy(() -> generator("not-a-key").generate())
                .isInstanceOfSatisfying(AppleApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AppleApiErrorCode.REVOKE_NOT_CONFIGURED));
    }

    @Test
    void neverPutsKeyMaterialInExceptionMessage() {
        // 실패 경로가 비밀을 흘리지 않는지 본다
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> generator("-----BEGIN PRIVATE KEY-----\nnot-base64!!\n-----END PRIVATE KEY-----").generate());

        assertThat(thrown).isNotNull();
        assertThat(String.valueOf(thrown.getMessage())).doesNotContain("not-base64");
    }

    private AppleClientSecretGenerator generator(String privateKey) {
        return new AppleClientSecretGenerator(new AppleAuthProperties(
                "https://appleid.apple.com/auth/keys",
                "https://appleid.apple.com",
                CLIENT_ID,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                TEAM_ID,
                KEY_ID,
                privateKey,
                "https://appleid.apple.com/auth/token",
                "https://appleid.apple.com/auth/revoke"
        ));
    }
}
