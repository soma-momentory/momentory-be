package com.momentory.report.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.report.infrastructure.WeeklyReportWritingRepository;
import com.momentory.report.infrastructure.ai.GeminiWeeklyReportWriter;
import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.restrecord.infrastructure.RestRecordRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/** 주간 리포트 v2 통합 검증 — 실제 HTTP + 인증 + DB 로 공책 한 벌과 AI 글 저장을 본다. AI 만 가짜로 둔다. */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReportV2ApiIntegrationTest {

    /** 대상 주 — 2026-08-16(일) ~ 2026-08-22(토), KST. 이미 끝난 주다. */
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 16);
    private static final GeminiWeeklyReportWriter.Draft DRAFT = new GeminiWeeklyReportWriter.Draft(
            "면접 스터디 이야기가 많았던 한 주예요", "월요일에는 대답이 막혔다고 적었어요.", "다시 간 건 멋진 일이에요.",
            "친구를 만난 날 괜찮았어요");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired DiaryRepository diaryRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired RestRecordRepository restRecordRepository;
    @Autowired WeeklyReportWritingRepository writingRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired SafetyPolicy safetyPolicy;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean GeminiWeeklyReportWriter writer;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        user = userRepository.saveAndFlush(User.create());
        reset(writer);
        when(writer.write(any(), any(), any(), any())).thenReturn(Optional.of(DRAFT));
    }

    @AfterEach
    void cleanUp() {
        writingRepository.deleteAllInBatch();
        restRecordRepository.deleteAllInBatch();
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("끝난 주 한 벌 — 표지 · 말 · 감정 · 괜찮았던 순간 · 쉼터 · 해낸 일이 함께 온다")
    void gathersPages() throws Exception {
        RetrospectState monday = retrospect("면접 대답이 자꾸 막혀서 답답했다", Emotion.ANXIOUS, "면접 스터디");
        seedDiary(monday, Emotion.ANXIOUS, SUNDAY.plusDays(1));
        seedDiary(retrospect("친구랑 오랜만에 한참 웃었다", Emotion.CALM, "친구와 저녁"), Emotion.CALM, SUNDAY.plusDays(2));
        seedDiary(retrospect("어깨가 가벼워진 기분이었다", Emotion.PROUD, "발표"), Emotion.PROUD, SUNDAY.plusDays(3));
        Schedule done = Schedule.createManual(user.getId(), SUNDAY.plusDays(2), "장보기", 1L);
        done.changeCompletion(true, null);
        scheduleRepository.saveAndFlush(done);
        seedRest(RestContent.BODY_RELEASE, SUNDAY.plusDays(2), true, RestMood.BETTER);
        seedRest(RestContent.WORRY_BOX, SUNDAY.plusDays(2), false, null);

        open("2026-08-19").andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-08-16"))
                .andExpect(jsonPath("$.newest").value(false))
                .andExpect(jsonPath("$.diaryCount").value(3))
                .andExpect(jsonPath("$.detailed").value(true))
                .andExpect(jsonPath("$.tone").value("NORMAL"))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].written").value(false))
                .andExpect(jsonPath("$.days[1].emotion").value("anxious"))
                .andExpect(jsonPath("$.restCount").value(1))
                .andExpect(jsonPath("$.coverLine").value(DRAFT.coverLine()))
                .andExpect(jsonPath("$.review").value(DRAFT.review()))
                .andExpect(jsonPath("$.okLine").value(DRAFT.okLine()))
                .andExpect(jsonPath("$.words.length()").value(3))
                .andExpect(jsonPath("$.words[0].text").value("면접 대답이 자꾸 막혀서 답답했다"))
                .andExpect(jsonPath("$.words[0].label").value("면접 스터디"))
                .andExpect(jsonPath("$.emotions[0].events[0]").value("면접 스터디"))
                .andExpect(jsonPath("$.okMoments.length()").value(2))
                .andExpect(jsonPath("$.okMoments[0].tasks[0]").value("장보기"))
                .andExpect(jsonPath("$.okMoments[0].betterRest").value("BODY_RELEASE"))
                .andExpect(jsonPath("$.rest.total").value(1))
                .andExpect(jsonPath("$.rest.places[0].content").value("BODY_RELEASE"))
                .andExpect(jsonPath("$.done.completedCount").value(1))
                .andExpect(jsonPath("$.done.perDay[2]").value(1))
                .andExpect(jsonPath("$.original").doesNotExist());
    }

    @Test
    @DisplayName("글은 한 번만 만들어 저장하고, 일기를 고치면 다음에 열 때 다시 만든다")
    void writesOnceAndRewritesAfterEdit() throws Exception {
        seedDiary(retrospect("친구랑 오랜만에 한참 웃었다", Emotion.CALM, "친구와 저녁"), Emotion.CALM, SUNDAY);

        open("2026-08-19").andExpect(status().isOk());
        open("2026-08-19").andExpect(status().isOk());
        verify(writer, times(1)).write(any(), any(), any(), any());

        jdbcTemplate.update("UPDATE diaries SET original = '고친 본문'");
        open("2026-08-19").andExpect(status().isOk());
        verify(writer, times(2)).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AI 가 실패하면 저장하지 않고 대체 표지만 보여 주며, 다음에 열 때 다시 시도한다")
    void fallsBackWithoutSaving() throws Exception {
        seedDiary(retrospect("친구랑 오랜만에 한참 웃었다", Emotion.CALM, "친구와 저녁"), Emotion.CALM, SUNDAY);
        when(writer.write(any(), any(), any(), any())).thenReturn(Optional.empty());

        open("2026-08-19").andExpect(status().isOk())
                .andExpect(jsonPath("$.coverLine").value("이 주에 일기를 1편 남겼어요"))
                .andExpect(jsonPath("$.review").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(writingRepository.count()).isZero();
        when(writer.write(any(), any(), any(), any())).thenReturn(Optional.of(DRAFT));
        open("2026-08-19").andExpect(jsonPath("$.coverLine").value(DRAFT.coverLine()));
    }

    @Test
    @DisplayName("위기 안내가 나온 주는 AI 를 부르지 않고, 그날의 말을 꺼내지 않으며, 도움받을 곳을 함께 준다")
    void crisisWeekStaysQuiet() throws Exception {
        RetrospectState crisis = retrospect("아무것도 하기 싫고 사라지고 싶다", Emotion.DEPRESSED, "야근");
        crisis.addAssistantMessage(safetyPolicy.guidanceFor(SafetyLevel.IMMINENT).title() + " 안내");
        seedDiary(crisis, Emotion.DEPRESSED, SUNDAY);

        open("2026-08-19").andExpect(status().isOk())
                .andExpect(jsonPath("$.tone").value("CRISIS"))
                .andExpect(jsonPath("$.words.length()").value(0))
                .andExpect(jsonPath("$.goodWords").doesNotExist())
                .andExpect(jsonPath("$.helpResources.length()").value(safetyPolicy.resources().size()));
        verify(writer, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("날짜 없이 열면 일기가 남은 가장 최근 주를 열고, 일기가 전혀 없으면 지난주를 비워서 준다")
    void opensLatestWrittenWeek() throws Exception {
        mockMvc.perform(authorized(get("/api/v2/reports/weekly"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.diaryCount").value(0))
                .andExpect(jsonPath("$.newest").value(true));
        verify(writer, never()).write(any(), any(), any(), any());

        seedDiary(retrospect("친구랑 오랜만에 한참 웃었다", Emotion.CALM, "친구와 저녁"), Emotion.CALM, SUNDAY.plusDays(4));
        mockMvc.perform(authorized(get("/api/v2/reports/weekly"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-08-16"))
                .andExpect(jsonPath("$.newest").value(false))
                .andExpect(jsonPath("$.detailed").value(false));
    }

    @Test
    @DisplayName("끝나지 않은 주는 글을 만들지 않는다")
    void doesNotWriteUnfinishedWeek() throws Exception {
        seedDiary(retrospect("친구랑 오랜만에 한참 웃었다", Emotion.CALM, "친구와 저녁"), Emotion.CALM,
                com.momentory.common.time.DayBoundary.today());
        open(com.momentory.common.time.DayBoundary.today().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.coverLine").value("이 주에 일기를 1편 남겼어요"));
        verify(writer, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("인증 없이는 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v2/reports/weekly")).andExpect(status().isUnauthorized());
    }

    private ResultActions open(String date) throws Exception {
        return mockMvc.perform(authorized(get("/api/v2/reports/weekly").param("date", date)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()));
    }

    private static RetrospectState retrospect(String word, Emotion emotion, String event) {
        RetrospectState state = new RetrospectState("s");
        state.addUserMessage(word);
        state.events(List.of(new ExtractedEvent(1, event, event, List.of(1))));
        state.emotions(List.of(new ExtractedEmotion(1, "표현", emotion, 2, null, word, List.of(1))));
        return state;
    }

    private void seedDiary(RetrospectState state, Emotion emotion, LocalDate date) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, codec.serialize(state)));
        // 정오(KST)에 쓴 일기
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.parse(date + "T03:00:00Z"), ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO diaries (user_id, retrospect_id, original, primary_emotion, emotions, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, user.getId(), retrospect.getId(), "본문", emotion.name(), null, at, at);
    }

    private void seedRest(RestContent content, LocalDate date, boolean completed, RestMood mood) {
        restRecordRepository.saveAndFlush(com.momentory.restrecord.domain.RestRecord.create(user.getId(), content, date,
                completed, mood));
    }
}
