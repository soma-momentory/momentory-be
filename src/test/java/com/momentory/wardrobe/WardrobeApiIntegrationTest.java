package com.momentory.wardrobe;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.schedule.application.ScheduleService;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
class WardrobeApiIntegrationTest {

    private static final String BASE = "/api/v1/wardrobe";

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
    @Autowired DiaryRepository diaryRepository;
    @Autowired ScheduleService scheduleService;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void checkInGivesEachRewardOnce() throws Exception {
        User user = user();
        diaryRepository.saveAndFlush(Diary.write(user.getId(), "오늘은 산책을 했다", Emotion.PROUD));
        var schedule = scheduleService.createManualSchedule(user.getId(), DayBoundary.today(), "빨래");
        scheduleService.changeCompletion(user.getId(), schedule.id(), true, null);

        perform(user, post(BASE + "/check-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tangerines").value(50))
                .andExpect(jsonPath("$.rewards.length()").value(3))
                .andExpect(jsonPath("$.quiet").value(false));
        perform(user, post(BASE + "/check-in"))
                .andExpect(jsonPath("$.tangerines").value(50))
                .andExpect(jsonPath("$.rewards.length()").value(0));
        perform(user, get(BASE))
                .andExpect(jsonPath("$.todayRewards", org.hamcrest.Matchers.containsInAnyOrder(
                        "CHECK_IN", "DIARY", "SCHEDULES_DONE")));
        assertThat(jdbc.queryForObject("select count(*) from tangerine_transactions where user_id = ?",
                Long.class, user.getId())).isEqualTo(3);
    }

    @Test
    void attendanceCellRestAndLetterAndUnfinishedSchedules() throws Exception {
        User user = user();
        LocalDate today = DayBoundary.today();
        scheduleService.createManualSchedule(user.getId(), today, "아직 안 함");
        rewardRow(user, "CHECK_IN", 10, today.minusDays(2));
        rewardRow(user, "CHECK_IN", 10, today.minusDays(1));
        Diary diary = diaryRepository.saveAndFlush(Diary.write(user.getId(), "사흘째", Emotion.PROUD));
        jdbc.update("insert into rest_records (user_id, content, record_date, completed) values (?, 'WORRY_BOX', ?, false)",
                user.getId(), Date.valueOf(today));
        jdbc.update("insert into letters (user_id, diary_id, letter_date, status, opened_at) values (?, ?, ?, 'READY', ?)",
                user.getId(), diary.getId(), Date.valueOf(today.minusDays(1)), Timestamp.from(Instant.now()));

        perform(user, get(BASE))
                .andExpect(jsonPath("$.attendanceDay").value(3))
                .andExpect(jsonPath("$.attendanceRewards.length()").value(7))
                .andExpect(jsonPath("$.welcomed").value(false));
        perform(user, post(BASE + "/check-in"))
                .andExpect(jsonPath("$.rewards[?(@.reason == 'CHECK_IN')].amount").value(20))
                .andExpect(jsonPath("$.rewards[*].reason", not(hasItem("REST"))))
                .andExpect(jsonPath("$.rewards[*].reason", hasItem("LETTER")))
                .andExpect(jsonPath("$.rewards[*].reason", not(hasItem("SCHEDULES_DONE"))))
                .andExpect(jsonPath("$.tangerines").value(60)); // 사흘째 출석 20 + 일기 30 + 편지 10 (중간에 나간 쉼터는 없음)
        perform(user, get(BASE)).andExpect(jsonPath("$.attendanceDay").value(3));

        // 중간에 나간 쉼터는 세지 않는다 — 끝까지 한 판이 생기면 그때 준다
        jdbc.update("insert into rest_records (user_id, content, record_date, completed, mood) values (?, 'SENSE_POND', ?, true, 'BETTER')",
                user.getId(), Date.valueOf(today));
        perform(user, post(BASE + "/check-in"))
                .andExpect(jsonPath("$.rewards[*].reason", hasItem("REST")))
                .andExpect(jsonPath("$.tangerines").value(70));
    }

    @Test
    void welcomeGiftOnlyOnce() throws Exception {
        User user = user();
        perform(user, post(BASE + "/welcome"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tangerines").value(100))
                .andExpect(jsonPath("$.rewards[0].reason").value("WELCOME"));
        // 받은 날을 옮겨도 다시 받지 않는다(평생 한 번)
        jdbc.update("update tangerine_transactions set reward_date = ? where user_id = ? and reason = 'WELCOME'",
                Date.valueOf(DayBoundary.today().minusDays(5)), user.getId());
        perform(user, post(BASE + "/welcome"))
                .andExpect(jsonPath("$.tangerines").value(100))
                .andExpect(jsonPath("$.rewards.length()").value(0));
        perform(user, get(BASE)).andExpect(jsonPath("$.welcomed").value(true));
    }

    @Test
    void checkInIsQuietOnCrisisDay() throws Exception {
        User user = user();
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(), RetrospectStatus.IN_PROGRESS,
                null, codec.serialize(new RetrospectState(UUID.randomUUID().toString()))));
        jdbc.update("insert into safety_events (user_id, retrospect_id, level, occurred_at) values (?, ?, 'RISK', ?)",
                user.getId(), retrospect.getId(), Timestamp.from(Instant.now()));

        perform(user, post(BASE + "/check-in"))
                .andExpect(jsonPath("$.quiet").value(true))
                .andExpect(jsonPath("$.tangerines").value(10));
    }

    @Test
    void purchaseChecksBalanceAndChargesOnce() throws Exception {
        User user = user();
        perform(user, post(BASE + "/items/hat-bucket/purchase"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TANGERINE_NOT_ENOUGH"));
        assertThat(owned(user)).isZero();

        rewardRow(user, "CHECK_IN", 300, DayBoundary.today().minusDays(10));
        jdbc.update("insert into tangerine_wallets (user_id, balance) values (?, 300)", user.getId());
        perform(user, post(BASE + "/items/hat-bucket/purchase"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tangerines").value(180));
        perform(user, post(BASE + "/items/hat-bucket/purchase"))
                .andExpect(jsonPath("$.tangerines").value(180));
        perform(user, post(BASE + "/items/hat-purple-beanie/purchase"))
                .andExpect(jsonPath("$.tangerines").value(180));
        perform(user, post(BASE + "/items/hat-nothing/purchase"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WARDROBE_ITEM_NOT_FOUND"));
        assertThat(owned(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount from tangerine_transactions where user_id = ? and reason = 'PURCHASE'",
                Integer.class, user.getId())).isEqualTo(-120);

        perform(user, get(BASE))
                .andExpect(jsonPath("$.tangerines").value(180))
                .andExpect(jsonPath("$.items.length()").value(53))
                .andExpect(jsonPath("$.items[?(@.id == 'hat-bucket')].owned").value(true))
                .andExpect(jsonPath("$.items[?(@.id == 'hat-cap')].owned").value(false))
                .andExpect(jsonPath("$.items[?(@.id == 'hat-chick')].price").value(200));
    }

    @Test
    void wearsOnlyOwnedItemsInTheirSlot() throws Exception {
        User user = user();
        perform(user, outfit("""
                {"hat": "hat-bucket", "eyes": null, "clothes": null}
                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WARDROBE_ITEM_NOT_OWNED"));
        perform(user, outfit("""
                {"hat": "eyes-glasses", "eyes": null, "clothes": null}
                """))
                .andExpect(status().isNotFound());

        perform(user, outfit("""
                {"hat": "hat-sheep-towel", "eyes": "eyes-face-smile", "clothes": "clothes-knit-vest"}
                """))
                .andExpect(status().isOk());
        perform(user, get(BASE))
                .andExpect(jsonPath("$.outfit.hat").value("hat-sheep-towel"))
                .andExpect(jsonPath("$.outfit.eyes").value("eyes-face-smile"))
                .andExpect(jsonPath("$.outfit.clothes").value("clothes-knit-vest"));

        perform(user, outfit("""
                {"hat": null, "eyes": null, "clothes": "clothes-knit-vest"}
                """))
                .andExpect(status().isOk());
        perform(user, get(BASE)).andExpect(jsonPath("$.outfit.hat").doesNotExist());
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder outfit(String body) {
        return put(BASE + "/outfit").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void rewardRow(User user, String reason, int amount, LocalDate day) {
        jdbc.update("insert into tangerine_transactions (user_id, reason, amount, reward_date) values (?, ?, ?, ?)",
                user.getId(), reason, amount, Date.valueOf(day));
    }

    private long owned(User user) {
        return jdbc.queryForObject("select count(*) from user_wardrobe_items where user_id = ?", Long.class, user.getId());
    }

    private User user() {
        return userRepository.saveAndFlush(User.create());
    }

    private ResultActions perform(User user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole())));
    }
}
