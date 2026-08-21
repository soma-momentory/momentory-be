package com.momentory.schedule.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.domain.ScheduleEmotion;
import com.momentory.schedule.domain.ScheduleSource;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class ScheduleControllerIntegrationTest {

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
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        scheduleRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void createsManualScheduleWithTrimmedTitleAndNextDisplayOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate date = LocalDate.of(2026, 8, 10);

        mockMvc.perform(create(user, "{\"date\":\"2026-08-10\",\"title\":\" 운동하기 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value("2026-08-10"))
                .andExpect(jsonPath("$.title").value("운동하기"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.emotion").doesNotExist())
                .andExpect(jsonPath("$.displayOrder").value(0))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.hidden").value(false));
        mockMvc.perform(create(user, "{\"date\":\"2026-08-10\",\"title\":\"독서하기\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayOrder").value(1));

        assertThat(scheduleRepository.findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(user.getId(), date))
                .extracting(Schedule::getTitle)
                .containsExactly("운동하기", "독서하기");
    }

    @Test
    void returnsOnlyCurrentUsersActiveSchedulesOrderedByDisplayOrderThenId() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        LocalDate date = LocalDate.of(2026, 8, 10);
        Schedule first = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "첫 번째", 0L));
        Schedule sameOrder = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "같은 순서", 0L));
        Schedule later = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "나중 순서", 1L));
        Schedule deleted = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "삭제됨", 2L));
        deleted.delete(java.time.Instant.now());
        scheduleRepository.saveAndFlush(deleted);
        scheduleRepository.saveAndFlush(Schedule.createManual(anotherUser.getId(), date, "다른 사용자", 0L));

        mockMvc.perform(getSchedules(user, date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules.length()").value(3))
                .andExpect(jsonPath("$.schedules[0].id").value(first.getId()))
                .andExpect(jsonPath("$.schedules[1].id").value(sameOrder.getId()))
                .andExpect(jsonPath("$.schedules[2].id").value(later.getId()));
    }

    @Test
    void updatesOnlyOwnedActiveSchedule() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));
        Schedule deleted = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "삭제된 일정", 1L));
        Schedule external = scheduleRepository.saveAndFlush(Schedule.createCalendar(
                user.getId(), "external-1", LocalDate.of(2026, 8, 10), "외부 일정", 2L
        ));
        deleted.delete(java.time.Instant.now());
        scheduleRepository.saveAndFlush(deleted);

        mockMvc.perform(update(user, schedule.getId(), "{\"date\":\"2026-08-11\",\"title\":\" 저녁 운동하기 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-11"))
                .andExpect(jsonPath("$.title").value("저녁 운동하기"));
        mockMvc.perform(update(anotherUser, schedule.getId(), "{\"date\":\"2026-08-11\",\"title\":\"변경\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("일정을 찾을 수 없습니다."));
        mockMvc.perform(update(user, deleted.getId(), "{\"date\":\"2026-08-11\",\"title\":\"변경\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
        mockMvc.perform(update(user, external.getId(), "{\"date\":\"2026-08-11\",\"title\":\"변경\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));

        Schedule updated = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(updated.getScheduleDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(updated.getTitle()).isEqualTo("저녁 운동하기");
    }

    @Test
    void softDeletesOnlyOwnedScheduleAndMakesRepeatDeleteIdempotent() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));

        mockMvc.perform(deleteSchedule(anotherUser, schedule.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
        mockMvc.perform(deleteSchedule(user, schedule.getId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(deleteSchedule(user, schedule.getId()))
                .andExpect(status().isNoContent());

        Schedule deleted = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(deleted.getDeletedAt()).isNotNull();
        mockMvc.perform(getSchedules(user, LocalDate.of(2026, 8, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules").isEmpty());
    }

    @Test
    void rejectsBlankTooLongAndInvalidDates() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String tooLongTitle = "a".repeat(256);

        mockMvc.perform(create(user, "{\"date\":\"2026-08-10\",\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(create(user, "{\"date\":\"2026-08-10\",\"title\":\"%s\"}".formatted(tooLongTitle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/schedules?date=2026-08-40").header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(delete("/api/v1/schedules/not-a-number").header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void completesScheduleWithEmotionAndReturnsItInDailyScheduleList() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate date = LocalDate.of(2026, 8, 10);
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "운동하기", 0L));

        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":true,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(schedule.getId()))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.emotion").value("PROUD"));

        Schedule completed = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(completed.isCompleted()).isTrue();
        assertThat(completed.getEmotion()).isEqualTo(com.momentory.schedule.domain.ScheduleEmotion.PROUD);
        mockMvc.perform(getSchedules(user, date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].completed").value(true))
                .andExpect(jsonPath("$.schedules[0].emotion").value("PROUD"));
    }

    @Test
    void completesScheduleWithoutEmotionAndChangesEmotionAfterCompletion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));

        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":true,\"emotion\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));
        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getEmotion()).isNull();

        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":true,\"emotion\":\"HAPPY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emotion").value("HAPPY"));
        assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getEmotion())
                .isEqualTo(com.momentory.schedule.domain.ScheduleEmotion.HAPPY);
    }

    @Test
    void cancellingCompletionClearsEmotion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));
        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":true,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":false,\"emotion\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false));

        Schedule cancelled = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(cancelled.isCompleted()).isFalse();
        assertThat(cancelled.getEmotion()).isNull();
    }

    @Test
    void rejectsEmotionForIncompleteScheduleAndUnsupportedEmotionCode() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));

        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":false,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("미완료 상태에서는 감정을 선택할 수 없습니다."));
        mockMvc.perform(changeCompletion(user, schedule.getId(), "{\"completed\":true,\"emotion\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Schedule unchanged = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertThat(unchanged.isCompleted()).isFalse();
        assertThat(unchanged.getEmotion()).isNull();
    }

    @Test
    void blocksCompletionChangeForAnotherUsersOrDeletedSchedule() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "운동하기", 0L));
        Schedule deleted = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "삭제됨", 1L));
        deleted.delete(java.time.Instant.now());
        scheduleRepository.saveAndFlush(deleted);

        mockMvc.perform(changeCompletion(anotherUser, schedule.getId(), "{\"completed\":true,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
        mockMvc.perform(changeCompletion(user, deleted.getId(), "{\"completed\":true,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    void completesExternalScheduleWithEmotion() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule external = scheduleRepository.saveAndFlush(Schedule.createCalendar(
                user.getId(), "external-1", LocalDate.of(2026, 8, 10), "외부 일정", 0L
        ));

        mockMvc.perform(changeCompletion(user, external.getId(), "{\"completed\":true,\"emotion\":\"CALM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emotion").value("CALM"));
        assertThat(scheduleRepository.findById(external.getId()).orElseThrow().getEmotion())
                .isEqualTo(com.momentory.schedule.domain.ScheduleEmotion.CALM);
    }

    @Test
    void reconcilesCalendarSnapshotAndRestoresDeletedScheduleWithoutClearingUserState() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule manual = Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "수동 일정", 0L);
        Schedule updated = Schedule.createCalendar(
                user.getId(), "calendar-updated", LocalDate.of(2026, 8, 10), "수정 전", 1L
        );
        Schedule removed = Schedule.createCalendar(
                user.getId(), "calendar-removed", LocalDate.of(2026, 8, 11), "삭제될 일정", 0L
        );
        Schedule restored = Schedule.createCalendar(
                user.getId(), "calendar-restored", LocalDate.of(2026, 8, 12), "복구 전", 0L
        );
        restored.changeCompletion(true, ScheduleEmotion.CALM);
        restored.changeHidden(true);
        restored.delete(java.time.Instant.parse("2026-08-13T00:00:00Z"));
        Schedule anotherUsers = Schedule.createCalendar(
                anotherUser.getId(), "calendar-removed", LocalDate.of(2026, 8, 11), "다른 사용자", 0L
        );
        scheduleRepository.saveAllAndFlush(List.of(manual, updated, removed, restored, anotherUsers));

        String body = """
                {
                  "from":"2026-08-01",
                  "to":"2026-08-31",
                  "events":[
                    {"externalId":"calendar-updated","date":"2026-08-15","title":"수정 후"},
                    {"externalId":"calendar-restored","date":"2026-08-16","title":"복구 후"},
                    {"externalId":"calendar-created","date":"2026-08-17","title":"삭제될 일정"}
                  ]
                }
                """;

        mockMvc.perform(sync(user, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.deleted").value(1));

        Schedule syncedSchedule = scheduleRepository.findById(updated.getId()).orElseThrow();
        assertThat(syncedSchedule.getScheduleDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(syncedSchedule.getTitle()).isEqualTo("수정 후");
        assertThat(scheduleRepository.findById(removed.getId()).orElseThrow().isDeleted()).isTrue();

        Schedule revived = scheduleRepository.findById(restored.getId()).orElseThrow();
        assertThat(revived.isDeleted()).isFalse();
        assertThat(revived.getScheduleDate()).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(revived.getTitle()).isEqualTo("복구 후");
        assertThat(revived.isCompleted()).isTrue();
        assertThat(revived.getEmotion()).isEqualTo(ScheduleEmotion.CALM);
        assertThat(revived.isHidden()).isTrue();

        assertThat(scheduleRepository.findById(manual.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(scheduleRepository.findById(anotherUsers.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(scheduleRepository.findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(
                user.getId(), LocalDate.of(2026, 8, 17)
        )).singleElement().satisfies(created -> {
            assertThat(created.getId()).isNotEqualTo(removed.getId());
            assertThat(created.getTitle()).isEqualTo(removed.getTitle());
            assertThat(created.getSource()).isEqualTo(ScheduleSource.CALENDAR);
            assertThat(created.getExternalId()).isEqualTo("calendar-created");
        });

        mockMvc.perform(sync(user, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deleted").value(0));
    }

    @Test
    void rejectsInvalidCalendarSnapshotsWithoutChangingSchedules() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule calendar = scheduleRepository.saveAndFlush(Schedule.createCalendar(
                user.getId(), "calendar-1", LocalDate.of(2026, 8, 10), "기존 일정", 0L
        ));

        mockMvc.perform(sync(user, """
                {"from":"2026-08-31","to":"2026-08-01","events":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("캘린더 동기화 요청이 올바르지 않습니다."));
        mockMvc.perform(sync(user, """
                {"from":"2026-08-01","to":"2026-08-31","events":[
                  {"externalId":"duplicate","date":"2026-08-10","title":"첫 번째"},
                  {"externalId":"duplicate","date":"2026-08-11","title":"두 번째"}
                ]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(sync(user, """
                {"from":"2026-08-01","to":"2026-08-31","events":[
                  {"externalId":"outside","date":"2026-09-01","title":"범위 밖"}
                ]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(sync(user, """
                {"from":"2026-08-01","to":"2026-08-31","events":[null]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("event는 필수입니다."));

        Schedule unchanged = scheduleRepository.findById(calendar.getId()).orElseThrow();
        assertThat(unchanged.isDeleted()).isFalse();
        assertThat(unchanged.getTitle()).isEqualTo("기존 일정");
    }

    @Test
    void changesVisibilityOnlyForOwnedActiveCalendarSchedule() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule calendar = scheduleRepository.saveAndFlush(Schedule.createCalendar(
                user.getId(), "calendar-1", LocalDate.of(2026, 8, 10), "캘린더 일정", 0L
        ));
        Schedule manual = scheduleRepository.saveAndFlush(Schedule.createManual(
                user.getId(), LocalDate.of(2026, 8, 10), "수동 일정", 1L
        ));

        mockMvc.perform(changeVisibility(user, calendar.getId(), "{\"hidden\":true}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(getSchedules(user, LocalDate.of(2026, 8, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].source").value("CALENDAR"))
                .andExpect(jsonPath("$.schedules[0].hidden").value(true));
        mockMvc.perform(changeVisibility(user, manual.getId(), "{\"hidden\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(changeVisibility(anotherUser, calendar.getId(), "{\"hidden\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(changeVisibility(user, calendar.getId(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("hidden은 필수입니다."));
        mockMvc.perform(deleteSchedule(user, calendar.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void changesScheduleOrderAndDailyLookupUsesChangedOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate date = LocalDate.of(2026, 8, 10);
        Schedule first = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "첫 번째", 0L));
        Schedule second = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "두 번째", 1L));
        Schedule third = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), date, "세 번째", 2L));

        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,%d,%d]}".formatted(third.getId(), first.getId(), second.getId())))
                .andExpect(status().isNoContent());

        assertThat(scheduleRepository.findById(third.getId()).orElseThrow().getDisplayOrder()).isZero();
        assertThat(scheduleRepository.findById(first.getId()).orElseThrow().getDisplayOrder()).isEqualTo(1L);
        assertThat(scheduleRepository.findById(second.getId()).orElseThrow().getDisplayOrder()).isEqualTo(2L);
        mockMvc.perform(getSchedules(user, date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].id").value(third.getId()))
                .andExpect(jsonPath("$.schedules[1].id").value(first.getId()))
                .andExpect(jsonPath("$.schedules[2].id").value(second.getId()));
    }

    @Test
    void rejectsDuplicateOrMissingScheduleIdsWhenChangingOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule first = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "첫 번째", 0L));
        Schedule second = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "두 번째", 1L));

        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,%d,%d]}".formatted(first.getId(), first.getId(), second.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("일정 순서 변경 요청이 올바르지 않습니다."));
        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d]}".formatted(first.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonexistentAndDifferentDateScheduleIdsWhenChangingOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "첫 번째", 0L));
        Schedule anotherDate = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 11), "다른 날짜", 0L));

        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,999999]}".formatted(schedule.getId())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,%d]}".formatted(schedule.getId(), anotherDate.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnotherUsersAndDeletedSchedulesWhenChangingOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule owned = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "내 일정", 0L));
        Schedule anotherUsers = scheduleRepository.saveAndFlush(Schedule.createManual(anotherUser.getId(), LocalDate.of(2026, 8, 10), "다른 사용자", 0L));
        Schedule deleted = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "삭제됨", 1L));
        deleted.delete(java.time.Instant.now());
        scheduleRepository.saveAndFlush(deleted);

        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,%d]}".formatted(owned.getId(), anotherUsers.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,%d]}".formatted(owned.getId(), deleted.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keepsAllDisplayOrdersWhenOrderValidationFails() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Schedule first = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "첫 번째", 0L));
        Schedule second = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 10), "두 번째", 1L));

        mockMvc.perform(changeOrder(user, "{\"date\":\"2026-08-10\",\"scheduleIds\":[%d,999999]}".formatted(second.getId())))
                .andExpect(status().isBadRequest());

        assertThat(scheduleRepository.findById(first.getId()).orElseThrow().getDisplayOrder()).isZero();
        assertThat(scheduleRepository.findById(second.getId()).orElseThrow().getDisplayOrder()).isEqualTo(1L);
    }

    @Test
    void returnsAuthenticationRequiredForDeletedAuthenticatedUserAcrossScheduleUseCases() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String token = bearerToken(user);
        userRepository.deleteById(user.getId());
        LocalDate date = LocalDate.of(2026, 8, 10);

        mockMvc.perform(get("/api/v1/schedules").param("date", date.toString()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
        mockMvc.perform(get("/api/v1/schedules/period").param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/schedules").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-08-10\",\"title\":\"운동하기\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/schedules/{scheduleId}", 1L).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-08-10\",\"title\":\"운동하기\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/schedules/{scheduleId}", 1L).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/schedules/{scheduleId}/completion", 1L).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true,\"emotion\":\"PROUD\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/schedules/order").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-08-10\",\"scheduleIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/schedules/sync").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-08-01\",\"to\":\"2026-08-31\",\"events\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/schedules/{scheduleId}/visibility", 1L)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isUnauthorized());

        assertThat(scheduleRepository.findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(user.getId(), date))
                .isEmpty();
    }

    @Test
    void returnsActiveVisibleSchedulesWithinPeriodOrderedByDateThenDisplayOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        Schedule earlierSecond = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 5), "5일 뒤 순서", 1L));
        Schedule earlierFirst = scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 5), "5일 앞 순서", 0L));
        Schedule later = scheduleRepository.saveAndFlush(Schedule.createCalendar(user.getId(), "cal-visible", LocalDate.of(2026, 8, 20), "캘린더 일정", 0L));
        Schedule hidden = Schedule.createCalendar(user.getId(), "cal-hidden", LocalDate.of(2026, 8, 21), "숨긴 캘린더", 0L);
        hidden.changeHidden(true);
        scheduleRepository.saveAndFlush(hidden);
        Schedule deleted = Schedule.createManual(user.getId(), LocalDate.of(2026, 8, 22), "삭제됨", 0L);
        deleted.delete(java.time.Instant.now());
        scheduleRepository.saveAndFlush(deleted);
        scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 7, 31), "범위 밖 앞", 0L));
        scheduleRepository.saveAndFlush(Schedule.createManual(user.getId(), LocalDate.of(2026, 9, 1), "범위 밖 뒤", 0L));
        scheduleRepository.saveAndFlush(Schedule.createManual(anotherUser.getId(), LocalDate.of(2026, 8, 10), "다른 사용자", 0L));

        mockMvc.perform(getPeriod(user, "2026-08-01", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules.length()").value(3))
                .andExpect(jsonPath("$.schedules[0].id").value(earlierFirst.getId()))
                .andExpect(jsonPath("$.schedules[1].id").value(earlierSecond.getId()))
                .andExpect(jsonPath("$.schedules[2].id").value(later.getId()))
                .andExpect(jsonPath("$.schedules[2].source").value("CALENDAR"))
                .andExpect(jsonPath("$.schedules[2].hidden").value(false));
        assertThat(hidden.getId()).isNotNull();
    }

    @Test
    void rejectsInvalidPeriodRange() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(getPeriod(user, "2026-08-31", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("일정 조회 기간이 올바르지 않습니다."));
        mockMvc.perform(getPeriod(user, "2026-01-01", "2027-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("일정 조회 기간이 올바르지 않습니다."));
        mockMvc.perform(get("/api/v1/schedules/period").param("from", "2026-08-01").header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(getPeriod(user, "2026-08-40", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    void acceptsMaximumAllowedPeriodSpan() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(getPeriod(user, "2026-01-01", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules").isEmpty());
    }

    @Test
    void documentsCompletionAndOrderEndpointsInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/schedules/{scheduleId}/completion'].put.responses.200.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/ScheduleCompletionResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/schedules/order'].patch.responses.204").exists())
                .andExpect(jsonPath("$.paths['/api/v1/schedules/order'].patch.responses.400.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(User user, String body) {
        return post("/api/v1/schedules")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(User user, Long scheduleId, String body) {
        return patch("/api/v1/schedules/{scheduleId}", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteSchedule(User user, Long scheduleId) {
        return delete("/api/v1/schedules/{scheduleId}", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changeCompletion(User user, Long scheduleId, String body) {
        return put("/api/v1/schedules/{scheduleId}/completion", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changeOrder(User user, String body) {
        return patch("/api/v1/schedules/order")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sync(User user, String body) {
        return post("/api/v1/schedules/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changeVisibility(
            User user,
            Long scheduleId,
            String body
    ) {
        return patch("/api/v1/schedules/{scheduleId}/visibility", scheduleId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getSchedules(User user, LocalDate date) {
        return get("/api/v1/schedules")
                .param("date", date.toString())
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getPeriod(User user, String from, String to) {
        return get("/api/v1/schedules/period")
                .param("from", from)
                .param("to", to)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
