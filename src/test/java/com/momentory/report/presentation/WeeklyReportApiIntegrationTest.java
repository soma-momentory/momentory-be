package com.momentory.report.presentation;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.TimeZonePolicy;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 주간 리포트 조회 API 통합 검증 — 실제 HTTP + 인증 + DB 로 한 주(일~토, KST)의 마음 일곱 칸과
 * 「이번 주 한눈에」 셈을 본다.
 *
 * <p>주 경계를 보려면 {@code created_at} 을 마음대로 심어야 하는데 엔티티는 {@code @PrePersist} 로
 * 생성 시각을 now 로 박으므로, 일기·행동 카드는 {@link JdbcTemplate} 으로 직접 넣는다(FK 를 위해
 * 회고는 실제로 저장).
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReportApiIntegrationTest {

    /** 대상 주 — 2026-08-16(일) ~ 2026-08-22(토), KST. */
    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 16);
    private static final LocalDate WEEK_END = LocalDate.of(2026, 8, 22);

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
    @Autowired DiaryRepository diaryRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM action_cards");
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("한 주치 리포트 — 마음 일곱 칸과 일정·행동 카드·일기 셈이 함께 온다")
    void weeklyReportGathersMoodAndCounts() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());

        // 마음 — 일(주 경계 04:00 KST)·월·화·목·토(주 경계 다음날 03:59:59 KST). 평온이 둘로 최다.
        seedDiary(user, Emotion.DEPRESSED, Instant.parse("2026-08-15T19:00:00Z")); // 8/16 04:00 KST
        seedDiary(user, Emotion.HAPPY, Instant.parse("2026-08-17T01:00:00Z"));
        seedDiary(user, Emotion.CALM, Instant.parse("2026-08-18T01:00:00Z"));
        seedDiary(user, Emotion.CALM, Instant.parse("2026-08-20T01:00:00Z"));
        seedDiary(user, Emotion.TIRED, Instant.parse("2026-08-22T14:59:59Z"));
        // 주 밖 — 지난주(일 경계 03:59:59 KST), 다음주 일요일 04:00 KST. 섞이면 최다 감정이 바뀐다.
        seedDiary(user, Emotion.CALM, Instant.parse("2026-08-15T18:59:59Z")); // 8/16 03:59:59 KST → 아직 8/15
        seedDiary(user, Emotion.CALM, Instant.parse("2026-08-22T19:00:00Z")); // 8/23 04:00 KST → 다음 주
        // 남의 일기도 섞이면 안 된다.
        seedDiary(other, Emotion.ANXIOUS, Instant.parse("2026-08-19T01:00:00Z"));

        // 일정 — 이번 주 다섯(완료 셋), 그리고 숨김·삭제·주 밖·남의 것은 빠진다.
        seedSchedule(user, WEEK_START, "일 일정", true);
        seedSchedule(user, WEEK_START, "일 미완료", false);
        seedSchedule(user, LocalDate.of(2026, 8, 19), "수 일정", true);
        seedSchedule(user, WEEK_END, "토 일정", true);
        seedSchedule(user, WEEK_END, "토 미완료", false);
        seedHiddenCalendarSchedule(user, LocalDate.of(2026, 8, 18), "숨긴 캘린더 일정");
        seedDeletedSchedule(user, LocalDate.of(2026, 8, 18), "지운 일정");
        seedSchedule(user, LocalDate.of(2026, 8, 23), "다음 주 일정", true);
        seedSchedule(other, WEEK_START, "남의 일정", true);

        // 행동 카드 — 이번 주 생성 넷(그중 실천 둘).
        seedActionCard(user, Instant.parse("2026-08-16T01:00:00Z"), null);
        seedActionCard(user, Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T05:00:00Z"));
        seedActionCard(user, Instant.parse("2026-08-19T01:00:00Z"),
                Instant.parse("2026-08-19T05:00:00Z"));
        seedActionCard(user, Instant.parse("2026-08-22T14:59:59Z"), null);
        // 지난주에 만들어 이번 주에 해본 카드 — 생성 기준이라 어느 쪽에도 잡히지 않는다.
        seedActionCard(user, Instant.parse("2026-08-09T01:00:00Z"),
                Instant.parse("2026-08-18T05:00:00Z"));
        seedActionCard(user, Instant.parse("2026-08-23T01:00:00Z"), null);
        seedActionCard(other, Instant.parse("2026-08-18T01:00:00Z"), null);

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("date", "2026-08-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-08-16"))
                .andExpect(jsonPath("$.endDate").value("2026-08-22"))
                .andExpect(jsonPath("$.dailyMoods.length()").value(7))
                .andExpect(jsonPath("$.dailyMoods[0].date").value("2026-08-16"))
                .andExpect(jsonPath("$.dailyMoods[0].emotion").value("depressed"))
                .andExpect(jsonPath("$.dailyMoods[1].emotion").value("happy"))
                .andExpect(jsonPath("$.dailyMoods[2].emotion").value("calm"))
                // 기록이 없는 날은 칸이 남되 감정만 비어 온다(필드는 사라지지 않는다).
                .andExpect(jsonPath("$.dailyMoods[3].date").value("2026-08-19"))
                .andExpect(jsonPath("$.dailyMoods[3].emotion").isEmpty())
                .andExpect(jsonPath("$.dailyMoods[4].emotion").value("calm"))
                .andExpect(jsonPath("$.dailyMoods[5].emotion").isEmpty())
                .andExpect(jsonPath("$.dailyMoods[6].date").value("2026-08-22"))
                .andExpect(jsonPath("$.dailyMoods[6].emotion").value("tired"))
                .andExpect(jsonPath("$.dominantEmotion").value("calm"))
                .andExpect(jsonPath("$.moodMessage")
                        .value("이번 주에는 평온한 마음을 가장 많이 느꼈어요. "
                                + "나를 편안하게 해준 환경이나 행동을 다음 주에도 이어가 보세요."))
                .andExpect(jsonPath("$.scheduleTotalCount").value(5))
                .andExpect(jsonPath("$.scheduleCompletedCount").value(3))
                .andExpect(jsonPath("$.actionCardCreatedCount").value(4))
                .andExpect(jsonPath("$.actionCardCompletedCount").value(2))
                .andExpect(jsonPath("$.diaryCount").value(5));
    }

    @Test
    @DisplayName("주에 속한 아무 날을 넣어도 같은 주(일~토)로 맞춰진다")
    void anyDayInTheWeekResolvesToTheSameWeek() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        seedDiary(user, Emotion.PROUD, Instant.parse("2026-08-18T01:00:00Z"));

        for (String date : new String[] {"2026-08-16", "2026-08-19", "2026-08-22"}) {
            mockMvc.perform(get("/api/v1/reports/weekly")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .param("date", date))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.startDate").value("2026-08-16"))
                    .andExpect(jsonPath("$.endDate").value("2026-08-22"))
                    .andExpect(jsonPath("$.diaryCount").value(1))
                    .andExpect(jsonPath("$.dominantEmotion").value("proud"));
        }
    }

    @Test
    @DisplayName("date 를 빼면 오늘(KST)이 속한 주를 돌려준다")
    void omittedDateFallsBackToThisWeek() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate thisSunday = LocalDate.now(TimeZonePolicy.DEFAULT_ZONE_ID)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value(thisSunday.toString()))
                .andExpect(jsonPath("$.endDate").value(thisSunday.plusDays(6).toString()));
    }

    @Test
    @DisplayName("최다 감정이 여럿이면 '여러 마음' 멘트가 오고 대표 감정은 비어 있다")
    void tiedEmotionsGiveMixedMessage() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        seedDiary(user, Emotion.ANXIOUS, Instant.parse("2026-08-17T01:00:00Z"));
        seedDiary(user, Emotion.LETHARGIC, Instant.parse("2026-08-18T01:00:00Z"));

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("date", "2026-08-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dominantEmotion").isEmpty())
                .andExpect(jsonPath("$.moodMessage").value("여러 마음이 번갈아 찾아온 한 주였어요."))
                .andExpect(jsonPath("$.diaryCount").value(2));
    }

    @Test
    @DisplayName("기록이 하나도 없는 주 — 일곱 칸은 그대로 비고 셈은 모두 0")
    void emptyWeekReturnsZeroCounts() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("date", "2026-08-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyMoods.length()").value(7))
                .andExpect(jsonPath("$.dailyMoods[0].emotion").isEmpty())
                .andExpect(jsonPath("$.dominantEmotion").isEmpty())
                .andExpect(jsonPath("$.moodMessage").value("아직 이번 주에 기록된 마음이 없어요."))
                .andExpect(jsonPath("$.scheduleTotalCount").value(0))
                .andExpect(jsonPath("$.scheduleCompletedCount").value(0))
                .andExpect(jsonPath("$.actionCardCreatedCount").value(0))
                .andExpect(jsonPath("$.actionCardCompletedCount").value(0))
                .andExpect(jsonPath("$.diaryCount").value(0));
    }

    @Test
    @DisplayName("잘못된 날짜 형식은 400")
    void malformedDateIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("date", "2026-13-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    @DisplayName("표현 범위 끝자락 날짜 — 주 경계를 못 잡아도 500 이 아니라 400")
    void dateAtTheEdgeOfTheCalendarIs400() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(get("/api/v1/reports/weekly")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("date", "+999999999-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    @DisplayName("인증 없으면 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/reports/weekly").param("date", "2026-08-21"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** 회고(FK 대상)를 실제 저장하고, 그에 딸린 일기를 지정한 {@code createdAt} 으로 직접 넣는다. */
    private void seedDiary(User user, Emotion currentEmotion, Instant createdAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, currentEmotion, "{}"));
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO diaries (user_id, retrospect_id, original, reframed, current_emotion,
                                     schedule_emotion, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospect.getId(), "본문", null, currentEmotion.name(), null, at, at);
    }

    /** {@code doneAt} 이 null 이면 아직 해보지 않은 카드다. */
    private void seedActionCard(User user, Instant createdAt, Instant doneAt) {
        Retrospect retrospect = retrospectRepository.saveAndFlush(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, RetroMode.REFRAME, null, Emotion.CALM, "{}"));
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        OffsetDateTime done = doneAt == null ? null : OffsetDateTime.ofInstant(doneAt, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO action_cards (user_id, retrospect_id, situation, target_action,
                                          created_date, from_rest_preference, done, done_at,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(), retrospect.getId(), "상황", "행동",
                createdAt.atZone(TimeZonePolicy.DEFAULT_ZONE_ID).toLocalDate(), false,
                doneAt != null, done, at, at);
    }

    private void seedSchedule(User user, LocalDate date, String title, boolean completed) {
        Schedule schedule = Schedule.createManual(user.getId(), date, title, 0L);
        if (completed) {
            schedule.changeCompletion(true, ScheduleEmotion.PROUD);
        }
        scheduleRepository.saveAndFlush(schedule);
    }

    private void seedHiddenCalendarSchedule(User user, LocalDate date, String title) {
        Schedule schedule = Schedule.createCalendar(user.getId(), "external-" + title, date, title, 0L);
        schedule.changeHidden(true);
        schedule.changeCompletion(true, ScheduleEmotion.PROUD);
        scheduleRepository.saveAndFlush(schedule);
    }

    private void seedDeletedSchedule(User user, LocalDate date, String title) {
        Schedule schedule = Schedule.createManual(user.getId(), date, title, 1L);
        schedule.changeCompletion(true, ScheduleEmotion.PROUD);
        schedule.delete(Instant.now());
        scheduleRepository.saveAndFlush(schedule);
    }

    private String bearer(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
