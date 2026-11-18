package com.momentory.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterPictureGenerator;
import com.momentory.letter.application.LetterVerification;
import com.momentory.letter.application.RiskJudgment;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterPicture;
import com.momentory.letter.domain.LetterPictureStatus;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.infrastructure.LetterPictureRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterVerifier;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer.Drawn;
import com.momentory.letter.infrastructure.ai.GeminiRiskJudge;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Cast;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Panel;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Plan;
import com.momentory.retrospect.application.RetrospectCompleted;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 바바의 그림일기 — 회고 커밋 뒤 편지에 이어 그리기, 그리지 않는 편지, 실패 뒤 다시 그리지 않기, 그림 API.
 * 장면 나누기 · 그리기 모델은 바꿔 끼운다(응답 해석은 {@code GeminiPictureDrawerTest} 가 본다).
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789",
        "momentory.letter.picture.enabled=true",
        "momentory.safety.letter-gate.mode=ON"
})
@Testcontainers(disabledWithoutDocker = true)
class LetterPictureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String DIARY = "친구들이랑 학교에서 떠들면서 놀았다. 같이 있어서 행복했다.";
    private static final Plan PLAN = new Plan(List.of(new Cast("friend", "rabbit")), List.of(
            new Panel("a classroom", "Baba chats with a rabbit friend", "big grin", "a rabbit"),
            new Panel("a school hallway", "they walk side by side", "beaming", "a rabbit"),
            new Panel("a bench", "they sit together", "tender smile", "a rabbit"),
            new Panel("a path home", "Baba walks home calmly", "peaceful", "none")));

    @MockitoBean GeminiLetterWriter writer;
    @MockitoBean GeminiRiskJudge judge;
    @MockitoBean GeminiLetterVerifier verifier;
    @MockitoBean GeminiScenePlanner planner;
    @MockitoBean GeminiPictureDrawer drawer;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired DiaryRepository diaryRepository;
    @Autowired LetterRepository letterRepository;
    @Autowired LetterPictureRepository pictureRepository;
    @Autowired LetterPictureGenerator pictureGenerator;
    @Autowired RetrospectStateCodec codec;
    @Autowired TransactionTemplate transaction;
    @Autowired ApplicationEventPublisher events;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        when(planner.plan(anyLong(), anyString())).thenReturn(Optional.of(PLAN));
        when(planner.model()).thenReturn("plan-model");
        when(drawer.draw(anyLong(), any())).thenReturn(Optional.of(new Drawn(png(600, 800), "image/png")));
        when(drawer.model()).thenReturn("image-model");
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(15)).until(() -> letterRepository.findAll().stream()
                .noneMatch(l -> l.getStatus() == LetterStatus.GENERATING)
                && pictureRepository.findAll().stream().noneMatch(p -> p.getStatus() == LetterPictureStatus.GENERATING));
        letterRepository.deleteAllInBatch();
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void readyLetterGetsPictureAfterRetrospectAndServesIt() throws Exception {
        when(judge.judge(any(), any(), anyList(), anyList())).thenReturn(Optional.of(new RiskJudgment(SafetyLevel.NONE,
                SafetyLevel.NONE, SafetyLevel.NONE, RiskJudgment.Trajectory.STABLE, false, List.of())));
        when(verifier.verify(any(), any(), any(), any())).thenReturn(LetterVerification.of(List.of()));
        when(writer.writeBody(any(), any(), anyList())).thenReturn(Optional.of(
                new Body(null, "친구가 곁에 있다는 것만으로 하루가 따뜻해졌네요.", "오늘 밤엔 친구 이야기부터 물어볼게요.")));
        User user = userRepository.saveAndFlush(User.create());

        Long diaryId = transaction.execute(status -> {
            Diary diary = diaryOf(user);
            events.publishEvent(new RetrospectCompleted(diary.getRetrospectId(), diary.getUserId(),
                    new RetrospectCompleted.DiaryData(null, DIARY, List.of()), null, List.of()));
            return diary.getId();
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findByDiaryId(diaryId)
                .flatMap(l -> pictureRepository.findById(l.getId()))
                .map(LetterPicture::isReady).orElse(false));
        Letter letter = letterRepository.findByDiaryId(diaryId).orElseThrow();
        assertThat(letter.getStatus()).isEqualTo(LetterStatus.READY);
        LetterPicture picture = pictureRepository.findById(letter.getId()).orElseThrow();
        assertThat(picture.getMimeType()).isEqualTo("image/jpeg");
        assertThat(picture.getImage()).startsWith((byte) 0xFF, (byte) 0xD8); // JPEG 로 줄여 저장했다
        assertThat(picture.getScenes()).contains("panels").contains("a classroom");
        // 그리기 모델은 장면만 받는다 — 일기 원문은 장면 나누기에만 간다.
        verify(planner).plan(letter.getId(), DIARY);

        // 편지는 다음 날 04:00 부터 열린다 — 어제 편지로 옮겨 연다.
        LocalDate yesterday = DayBoundary.today().minusDays(1);
        jdbc.update("update letters set letter_date = ? where id = ?", yesterday, letter.getId());

        mockMvc.perform(authed(get("/api/v1/letters/{date}", yesterday), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPicture").value(true));
        String body = mockMvc.perform(authed(get("/api/v1/letters/{id}/picture", letter.getId()), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andReturn().getResponse().getContentAsString();
        assertThat(image(body).getHeight()).isGreaterThan(image(body).getWidth());

    }

    @Test
    void pictureIsHiddenFromOthersAndBeforeLetterOpens() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        Letter letter = readyLetter(user, DayBoundary.today(), "NORMAL");
        pictureGenerator.generateForDiary(letter.getDiaryId());
        assertThat(pictureRepository.findById(letter.getId()).map(LetterPicture::isReady)).contains(true);

        // 오늘 일기의 편지는 아직 열리지 않았다 — 그림도 없는 것처럼 다룬다.
        mockMvc.perform(authed(get("/api/v1/letters/{id}/picture", letter.getId()), user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LETTER_NOT_FOUND"));

        jdbc.update("update letters set letter_date = ? where id = ?", DayBoundary.today().minusDays(1), letter.getId());
        mockMvc.perform(authed(get("/api/v1/letters/{id}/picture", letter.getId()), other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LETTER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/letters/{id}/picture", letter.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyLettersThatPassedSafetyGetPicture() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate yesterday = DayBoundary.today().minusDays(1);
        Letter gentle = readyLetter(user, yesterday, "GENTLE");
        Letter fallback = readyLetter(user, yesterday.minusDays(1), null);
        jdbc.update("update letters set status = 'FALLBACK' where id = ?", fallback.getId());
        Letter quiet = readyLetter(user, yesterday.minusDays(2), "QUIET");
        jdbc.update("update letters set status = 'QUIET' where id = ?", quiet.getId());

        Letter unjudged = readyLetter(user, yesterday.minusDays(3), null); // 게이트 OFF · SHADOW 로 만든 편지

        for (Letter l : List.of(gentle, fallback, quiet, unjudged)) {
            pictureGenerator.generateForDiary(l.getDiaryId());
        }

        assertThat(pictureRepository.count()).isZero();
        verify(planner, never()).plan(anyLong(), anyString());
        mockMvc.perform(authed(get("/api/v1/letters/{date}", yesterday), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPicture").value(false));
        mockMvc.perform(authed(get("/api/v1/letters/{id}/picture", gentle.getId()), user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LETTER_PICTURE_NOT_FOUND"));
    }

    @Test
    void pictureIsWithheldWhenSafetyVerdictChangesAfterDrawing() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate yesterday = DayBoundary.today().minusDays(1);
        Letter letter = readyLetter(user, yesterday, "NORMAL");
        pictureGenerator.generateForDiary(letter.getDiaryId());
        mockMvc.perform(authed(get("/api/v1/letters/{date}", yesterday), user))
                .andExpect(jsonPath("$.hasPicture").value(true));

        // 일기를 고쳐 다시 판정한 경우, 그리고 열 때 위기 기록이 확인되어 조용한 편지로 바뀐 경우.
        for (String change : List.of("safety_policy = 'GENTLE'", "status = 'QUIET', safety_policy = 'QUIET'")) {
            jdbc.update("update letters set " + change + " where id = ?", letter.getId());

            mockMvc.perform(authed(get("/api/v1/letters/{date}", yesterday), user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasPicture").value(false));
            mockMvc.perform(authed(get("/api/v1/letters/{id}/picture", letter.getId()), user))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LETTER_PICTURE_NOT_FOUND"));
        }
    }

    @Test
    void failedPictureStaysFailedAndIsNeverDrawnAgain() throws Exception {
        when(planner.plan(anyLong(), anyString())).thenReturn(Optional.empty());
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = readyLetter(user, DayBoundary.today().minusDays(1), "NORMAL");

        pictureGenerator.generateForDiary(letter.getDiaryId());
        pictureGenerator.generateForDiary(letter.getDiaryId());

        LetterPicture picture = pictureRepository.findById(letter.getId()).orElseThrow();
        assertThat(picture.getStatus()).isEqualTo(LetterPictureStatus.FAILED);
        assertThat(picture.getImage()).isNull();
        verify(planner, times(1)).plan(anyLong(), anyString());
        verify(drawer, never()).draw(anyLong(), any());
        mockMvc.perform(authed(get("/api/v1/letters/{date}", DayBoundary.today().minusDays(1)), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPicture").value(false));
    }

    @Test
    void deletingDiaryDeletesPicture() {
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = readyLetter(user, DayBoundary.today().minusDays(1), "NORMAL");
        pictureGenerator.generateForDiary(letter.getDiaryId());
        assertThat(pictureRepository.existsById(letter.getId())).isTrue();

        jdbc.update("delete from diaries where id = ?", letter.getDiaryId());

        assertThat(pictureRepository.existsById(letter.getId())).isFalse();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private Diary diaryOf(User user) {
        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        state.addAssistantMessage("오늘 어떤 일이 있었어요?");
        state.addUserMessage("친구들이랑 놀아서 좋았어요");
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, codec.serialize(state)));
        return diaryRepository.save(Diary.create(user.getId(), retrospect.getId(), DIARY, null, null));
    }

    /** 다 만든 AI 편지 한 통 — 회고 흐름 없이 바로 넣는다. */
    private Letter readyLetter(User user, LocalDate date, String policy) {
        Diary diary = diaryOf(user);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), date));
        jdbc.update("update letters set status = 'READY', insight = '편지 본문', safety_policy = ? where id = ?",
                policy, letter.getId());
        return letterRepository.findById(letter.getId()).orElseThrow();
    }

    private static byte[] png(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(240, 200, 150));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static BufferedImage image(String responseBody) throws IOException {
        String base64 = responseBody.replaceAll(".*\"image\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()));
    }
}
