package com.momentory.weeklyhappiness.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.user.withdrawal.application.UserDeletionService;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessEntry;
import com.momentory.weeklyhappiness.infrastructure.WeeklyHappinessEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class WeeklyHappinessControllerIntegrationTest {

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
    @Autowired WeeklyHappinessEntryRepository entryRepository;
    @Autowired UserDeletionService userDeletionService;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final LocalDate thisWeek = WeeklyHappinessEntry.thisWeekStart();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        entryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void returnsEmptyWeekWhenNothingWritten() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(getWeek(user, thisWeek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value(thisWeek.toString()))
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.firstWeekStart").doesNotExist());
    }

    @Test
    void savesWeekAndReadsItBackInCategoryOrderWithStrippedContent() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(putWeek(user, thisWeek, """
                        {"entries":[
                          {"category":"REST","content":"  자기 전에 음악 듣기  "},
                          {"category":"MOMENT","content":"친구와 카페에서 수다 떨기"}
                        ]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value(thisWeek.toString()))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].category").value("MOMENT"))
                .andExpect(jsonPath("$.entries[1].category").value("REST"))
                .andExpect(jsonPath("$.entries[1].content").value("자기 전에 음악 듣기"))
                .andExpect(jsonPath("$.firstWeekStart").value(thisWeek.toString()));

        mockMvc.perform(getWeek(user, thisWeek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].category").value("MOMENT"))
                .andExpect(jsonPath("$.entries[0].content").value("친구와 카페에서 수다 떨기"))
                .andExpect(jsonPath("$.entries[1].content").value("자기 전에 음악 듣기"));

        assertThat(contentsOf(user, thisWeek)).containsExactlyInAnyOrderEntriesOf(Map.of(
                WeeklyHappinessCategory.MOMENT, "친구와 카페에서 수다 떨기",
                WeeklyHappinessCategory.REST, "자기 전에 음악 듣기"));
    }

    @Test
    void overwritesAndClearsFieldsAndRepeatingTheSameRequestChangesNothing() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(putWeek(user, thisWeek, """
                        {"entries":[
                          {"category":"FOOD","content":"마라탕"},
                          {"category":"PLACE","content":"공원"},
                          {"category":"PERSON","content":"친구"}
                        ]}
                        """))
                .andExpect(status().isOk());

        // FOOD 는 고치고, PLACE 는 비우고(공백), PERSON 은 보내지 않아 지운다
        String replace = """
                {"entries":[
                  {"category":"FOOD","content":"떡볶이"},
                  {"category":"PLACE","content":"   "}
                ]}
                """;
        mockMvc.perform(putWeek(user, thisWeek, replace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].category").value("FOOD"))
                .andExpect(jsonPath("$.entries[0].content").value("떡볶이"));
        mockMvc.perform(putWeek(user, thisWeek, replace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));

        assertThat(contentsOf(user, thisWeek)).containsExactlyEntriesOf(Map.of(WeeklyHappinessCategory.FOOD, "떡볶이"));
        assertThat(entryRepository.count()).isEqualTo(1);

        mockMvc.perform(putWeek(user, thisWeek, "{\"entries\":[{\"category\":\"FOOD\",\"content\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.firstWeekStart").doesNotExist());
        assertThat(entryRepository.count()).isZero();
    }

    @Test
    void keepsWeeksApartAndReportsTheFirstWrittenWeek() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate threeWeeksAgo = thisWeek.minusWeeks(3);
        LocalDate lastWeek = thisWeek.minusWeeks(1);
        mockMvc.perform(putWeek(user, threeWeeksAgo, "{\"entries\":[{\"category\":\"PROUD\",\"content\":\"과제 마무리\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(putWeek(user, lastWeek, "{\"entries\":[{\"category\":\"CONTENT\",\"content\":\"요즘 듣는 노래\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(getWeek(user, lastWeek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].category").value("CONTENT"))
                .andExpect(jsonPath("$.firstWeekStart").value(threeWeeksAgo.toString()));
        mockMvc.perform(getWeek(user, thisWeek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.firstWeekStart").value(threeWeeksAgo.toString()));
    }

    @Test
    void rejectsWeekStartThatIsNotSundayOrInTheFuture() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String body = "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"마라탕\"}]}";
        LocalDate monday = thisWeek.plusDays(1);
        LocalDate nextWeek = thisWeek.plusWeeks(1);

        mockMvc.perform(putWeek(user, monday, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEEKLY_HAPPINESS_INVALID_WEEK_START"))
                .andExpect(jsonPath("$.message").value("주의 첫날(일요일) 날짜를 보내주세요."));
        mockMvc.perform(getWeek(user, monday))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEEKLY_HAPPINESS_INVALID_WEEK_START"));
        mockMvc.perform(putWeek(user, nextWeek, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEEKLY_HAPPINESS_FUTURE_WEEK"))
                .andExpect(jsonPath("$.message").value("아직 오지 않은 주입니다."));
        mockMvc.perform(getWeek(user, nextWeek))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEEKLY_HAPPINESS_FUTURE_WEEK"));
        mockMvc.perform(get("/api/v1/weekly-happiness").param("weekStart", "not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(get("/api/v1/weekly-happiness").header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(put("/api/v1/weekly-happiness/not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));

        assertThat(entryRepository.count()).isZero();
    }

    @Test
    void rejectsInvalidBodiesWithoutSaving() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String tooLong = "가".repeat(WeeklyHappinessEntry.CONTENT_MAX_LENGTH + 1);

        expectBadRequest(user, "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"%s\"}]}".formatted(tooLong),
                "주간 행복 한 칸은 최대 100자입니다.");
        expectBadRequest(user, "{}", "적은 칸 목록을 보내주세요.");
        expectBadRequest(user, "{\"entries\":[{\"content\":\"마라탕\"}]}", "칸을 골라주세요.");
        expectBadRequest(user, "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"a\"},{\"category\":\"FOOD\",\"content\":\"b\"}]}",
                "같은 칸을 두 번 보낼 수 없습니다.");
        expectBadRequest(user, "{\"entries\":[{\"category\":\"WEATHER\",\"content\":\"맑음\"}]}", "잘못된 요청입니다.");
        expectBadRequest(user, "{\"entries\":[null]}", "잘못된 요청입니다.");
        expectBadRequest(user, "not-json", "잘못된 요청입니다.");
        assertThat(entryRepository.count()).isZero();

        // 정확히 100자는 들어간다
        mockMvc.perform(putWeek(user, thisWeek,
                        "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"%s\"}]}".formatted("가".repeat(WeeklyHappinessEntry.CONTENT_MAX_LENGTH))))
                .andExpect(status().isOk());
        assertThat(entryRepository.count()).isEqualTo(1);
    }

    @Test
    void neverShowsOrTouchesAnotherUsersWeek() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        mockMvc.perform(putWeek(anotherUser, thisWeek, "{\"entries\":[{\"category\":\"PERSON\",\"content\":\"다른 사람의 글\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(getWeek(user, thisWeek))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.firstWeekStart").doesNotExist());
        // 내 주를 비워도 남의 글은 그대로다
        mockMvc.perform(putWeek(user, thisWeek, "{\"entries\":[]}"))
                .andExpect(status().isOk());

        assertThat(contentsOf(anotherUser, thisWeek)).containsExactlyEntriesOf(Map.of(WeeklyHappinessCategory.PERSON, "다른 사람의 글"));
    }

    @Test
    void concurrentReplacesOfTheSameWeekNeverMerge() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> food = pool.submit(concurrentPut(start, user, "FOOD"));
                Future<Integer> place = pool.submit(concurrentPut(start, user, "PLACE"));
                start.countDown();
                assertThat(food.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(place.get(30, TimeUnit.SECONDS)).isEqualTo(200);

                // 둘 중 나중에 끝난 요청의 기록장 그대로 — 두 칸이 함께 남으면 서로의 「바꾸기」가 섞인 것이다
                assertThat(contentsOf(user, thisWeek)).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> concurrentPut(CountDownLatch start, User user, String category) {
        return () -> {
            start.await();
            return mockMvc.perform(putWeek(user, thisWeek,
                            "{\"entries\":[{\"category\":\"%s\",\"content\":\"글\"}]}".formatted(category)))
                    .andReturn().getResponse().getStatus();
        };
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/weekly-happiness").param("weekStart", thisWeek.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(put("/api/v1/weekly-happiness/" + thisWeek)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"category\":\"FOOD\",\"content\":\"마라탕\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(entryRepository.count()).isZero();
    }

    @Test
    void removesEntriesWhenUserWithdraws() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        mockMvc.perform(putWeek(user, thisWeek, "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"마라탕\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(putWeek(anotherUser, thisWeek, "{\"entries\":[{\"category\":\"FOOD\",\"content\":\"떡볶이\"}]}"))
                .andExpect(status().isOk());

        userDeletionService.delete(user.getId());

        assertThat(entryRepository.findAll())
                .singleElement()
                .satisfies(left -> assertThat(left.getUserId()).isEqualTo(anotherUser.getId()));
    }

    @Test
    void databaseRejectsNonSundayWeekAndUnknownCategory() {
        User user = userRepository.saveAndFlush(User.create());
        String insert = "INSERT INTO weekly_happiness_entries (user_id, week_start, category, content) VALUES (?, ?, ?, ?)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), thisWeek.plusDays(1), "FOOD", "마라탕"))
                .hasMessageContaining("chk_weekly_happiness_entries_week_start_sunday");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), thisWeek, "WEATHER", "맑음"))
                .hasMessageContaining("chk_weekly_happiness_entries_category");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user.getId(), thisWeek, "FOOD", "  "))
                .hasMessageContaining("chk_weekly_happiness_entries_content_not_blank");
    }

    private void expectBadRequest(User user, String body, String message) throws Exception {
        mockMvc.perform(putWeek(user, thisWeek, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private Map<WeeklyHappinessCategory, String> contentsOf(User user, LocalDate weekStart) {
        return entryRepository.findByUserIdAndWeekStart(user.getId(), weekStart).stream()
                .collect(Collectors.toMap(WeeklyHappinessEntry::getCategory, WeeklyHappinessEntry::getContent));
    }

    private MockHttpServletRequestBuilder getWeek(User user, LocalDate weekStart) {
        return get("/api/v1/weekly-happiness")
                .param("weekStart", weekStart.toString())
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private MockHttpServletRequestBuilder putWeek(User user, LocalDate weekStart, String body) {
        return put("/api/v1/weekly-happiness/" + weekStart)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
