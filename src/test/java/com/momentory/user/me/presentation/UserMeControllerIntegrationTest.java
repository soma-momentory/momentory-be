package com.momentory.user.me.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.auth.token.application.RefreshTokenIssuer;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.auth.security.JwtProperties;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserRole;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class UserMeControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired UserProfileRepository userProfileRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired RefreshTokenIssuer refreshTokenIssuer;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtProperties jwtProperties;
    @Autowired ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        refreshTokenRepository.deleteAllInBatch();
        // 프로필이 사용자를 참조한다 — 먼저 지우지 않으면 FK 로 막힌다
        userProfileRepository.deleteAll();
        userRepository.deleteAllInBatch();
    }

    @Test
    void returnsCurrentDatabaseUserStateThroughLoginPrincipal() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String tokenWithStaleRole = accessTokenIssuer.issueAccessToken(user.getId(), UserRole.ADMIN);

        mockMvc.perform(me(tokenWithStaleRole))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.onboardingRequired").value(true));
    }

    @Test
    void returnsOnboardingRequiredFalseForCompletedUser() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        user.completeOnboarding();
        userRepository.saveAndFlush(user);

        mockMvc.perform(me(accessTokenIssuer.issueAccessToken(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false));
    }

    @Test
    void returnsOnboardingProfileSoClientCanRestoreItAfterRestart() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String token = accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());

        // 온보딩 전에는 프로필 자리가 아예 없다 — 빈 값을 지어내지 않는다
        mockMvc.perform(me(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").doesNotExist());

        mockMvc.perform(put("/api/v1/users/me/onboarding")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"지은","age":25,"gender":"FEMALE",
                                 "interestAreas":["CAREER","OTHER"],"otherInterestDetail":"에세이 글쓰기",
                                 "restMethods":["READING","OTHER"],"otherRestMethodDetail":"따뜻한 차 마시기",
                                 "reflectionTime":"21:30","calendarIntegrationEnabled":true,"notificationEnabled":true}
                                """))
                .andExpect(status().isOk());

        // 보낸 그대로 돌아온다 — 특히 시각은 보낸 형식(HH:mm)과 같아야 왕복에 어긋나지 않는다
        mockMvc.perform(me(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(false))
                .andExpect(jsonPath("$.profile.nickname").value("지은"))
                .andExpect(jsonPath("$.profile.age").value(25))
                .andExpect(jsonPath("$.profile.gender").value("FEMALE"))
                .andExpect(jsonPath("$.profile.interestAreas", containsInAnyOrder("CAREER", "OTHER")))
                .andExpect(jsonPath("$.profile.otherInterestDetail").value("에세이 글쓰기"))
                .andExpect(jsonPath("$.profile.restMethods", containsInAnyOrder("READING", "OTHER")))
                .andExpect(jsonPath("$.profile.otherRestMethodDetail").value("따뜻한 차 마시기"))
                .andExpect(jsonPath("$.profile.reflectionTime").value("21:30"))
                .andExpect(jsonPath("$.profile.calendarIntegrationEnabled").value(true))
                .andExpect(jsonPath("$.profile.notificationEnabled").value(true));
    }

    @Test
    void rejectsMissingExpiredAndTamperedAccessTokens() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        String expired = issueToken(1L, UserRole.USER, Instant.now().minusSeconds(120));
        mockMvc.perform(me(expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        String valid = accessTokenIssuer.issueAccessToken(1L, UserRole.USER);
        mockMvc.perform(me(tamper(valid)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsTokenWhoseUserNoLongerExists() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Long deletedUserId = user.getId();
        userRepository.deleteById(deletedUserId);
        userRepository.flush();
        String token = accessTokenIssuer.issueAccessToken(deletedUserId, UserRole.USER);

        mockMvc.perform(me(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    void acceptsAccessTokenIssuedByRefreshTokenReissue() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String refreshToken = "refresh-" + UUID.randomUUID();
        refreshTokenRepository.saveAndFlush(RefreshToken.create(
                user,
                refreshTokenIssuer.hash(refreshToken),
                Instant.now().plusSeconds(3600)
        ));

        MvcResult reissue = mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(reissue.getResponse().getContentAsString());

        mockMvc.perform(me(response.get("accessToken").stringValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder me(String accessToken) {
        return get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    private String issueToken(Long userId, UserRole role, Instant expiresAt) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(expiresAt.isBefore(now) ? expiresAt.minusSeconds(60) : now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    private String tamper(String token) {
        String[] parts = token.split("\\.");
        String signature = parts[2];
        char firstCharacter = signature.charAt(0);
        return parts[0] + "." + parts[1] + "."
                + (firstCharacter == 'A' ? 'B' : 'A') + signature.substring(1);
    }
}
