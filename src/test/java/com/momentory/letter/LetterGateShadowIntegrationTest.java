package com.momentory.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterGenerator;
import com.momentory.letter.application.LetterVerification;
import com.momentory.letter.application.LetterVerification.Target;
import com.momentory.letter.application.LetterVerification.Violation;
import com.momentory.letter.application.RiskJudgment;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.letter.domain.LetterRules;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterVerifier;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.ActionPick;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.letter.infrastructure.ai.GeminiRiskJudge;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 편지 안전 게이트 그림자 모드(설계서 6.4절 V6 · VT8) — 판정 · 검증을 돌려 기록만 하고, 사용자에게는 지금 경로의
 * 편지를 그대로 보낸다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789",
        "momentory.safety.letter-gate.mode=SHADOW"
})
@Testcontainers(disabledWithoutDocker = true)
class LetterGateShadowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String USER_WORD = "발표 전에 손이 계속 떨렸어요";
    private static final String DIARY = "오늘은 팀 발표가 있었다. 시작 전에 손이 떨렸지만 끝까지 말했다.";
    private static final Body BODY = new Body(USER_WORD, "떨렸던 건 그만큼 잘하고 싶었기 때문이었을지도 몰라요.",
            "오늘 밤엔 발표 뒤 마음부터 물어볼게요.");
    private static final RiskJudgment CLEAN = judgment(SafetyLevel.NONE, SafetyLevel.NONE, SafetyLevel.NONE, List.of());
    private static final LetterVerification PASS = LetterVerification.of(List.of());
    private static final LetterVerification MINOR = LetterVerification.of(List.of(
            new Violation("R3", false, Target.INSIGHT, "잘하고 싶었기", "넘겨짚지 않는다")));

    @MockitoBean GeminiLetterWriter writer;
    @MockitoBean GeminiRiskJudge judge;
    @MockitoBean GeminiLetterVerifier verifier;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired DiaryRepository diaryRepository;
    @Autowired LetterRepository letterRepository;
    @Autowired LetterActionRepository letterActionRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired LetterGenerator generator;
    @Autowired NeedActionCatalog catalog;
    @Autowired SafetyPolicy safetyPolicy;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        user = userRepository.saveAndFlush(User.create());
        when(writer.writeBody(any())).thenReturn(Optional.of(BODY));
        when(judge.judge(any(), any(), anyList(), anyList())).thenReturn(Optional.of(CLEAN));
    }

    @AfterEach
    void cleanUp() {
        letterRepository.deleteAllInBatch();
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void vt8_shadowRecordsVerdictButDeliversCurrentLetter() {
        when(verifier.verify(any(), any(), any(), any())).thenReturn(LetterVerification.of(List.of(
                new Violation("R1", true, Target.INSIGHT, "잘하고 싶었기", null))));

        Letter letter = generate();

        assertThat(letter.getStatus()).isEqualTo(LetterStatus.READY);
        assertThat(letter.getQuote()).isEqualTo(USER_WORD);
        assertThat(letter.getSafetyPolicy()).isNull();
        verify(judge, times(1)).judge(any(), any(), anyList(), anyList());
        verify(verifier, times(1)).verify(any(), any(), any(), any());
        verify(writer, never()).writeBody(any(), any(), anyList());
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private Letter generate() {
        return generate(List.of(USER_WORD));
    }

    private Letter generate(List<String> userWords) {
        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        state.addAssistantMessage("오늘 어떤 일이 있었어요?");
        userWords.forEach(state::addUserMessage);
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(), RetrospectStatus.COMPLETED,
                null, codec.serialize(state)));
        Diary diary = diaryRepository.save(Diary.create(user.getId(), retrospect.getId(), DIARY, null, null));
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        generator.generateForDiary(diary.getId());
        return letterRepository.findById(letter.getId()).orElseThrow();
    }

    private void pastDiary(String text) {
        Retrospect r = retrospectRepository.save(Retrospect.start(user.getId(), RetrospectStatus.COMPLETED, null,
                codec.serialize(new RetrospectState(UUID.randomUUID().toString()))));
        Diary d = diaryRepository.saveAndFlush(Diary.create(user.getId(), r.getId(), text, null, null));
        // 사흘 전 일기로 옮긴다(created_at 은 엔티티로는 바꿀 수 없다)
        jdbc.update("update diaries set created_at = ? where id = ?",
                java.sql.Timestamp.from(DayBoundary.startOfDay(yesterday().minusDays(3)).plusSeconds(3600)), d.getId());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed() {
        return get("/api/v1/letters/" + yesterday()).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()));
    }

    private static RiskJudgment judgment(SafetyLevel current, SafetyLevel mentioned, SafetyLevel history,
            List<RiskJudgment.Evidence> evidence) {
        return new RiskJudgment(current, mentioned, history, RiskJudgment.Trajectory.STABLE, false, evidence);
    }

    private static LocalDate yesterday() {
        return DayBoundary.today().minusDays(1);
    }
}
