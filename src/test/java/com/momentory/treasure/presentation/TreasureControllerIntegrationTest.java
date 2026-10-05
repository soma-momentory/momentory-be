package com.momentory.treasure.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.treasure.domain.Treasure;
import com.momentory.treasure.infrastructure.TreasureRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class TreasureControllerIntegrationTest {

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
    @Autowired TreasureRepository treasureRepository;
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
        treasureRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void addsTreasureToTodayAndStripsContent() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postTreasure(user, "  친구에게 먼저 연락했어요.  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.date").value(DayBoundary.today().toString()))
                .andExpect(jsonPath("$.content").value("친구에게 먼저 연락했어요."))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(treasureRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getContent()).isEqualTo("친구에게 먼저 연락했어요.");
                    assertThat(saved.getTreasureDate()).isEqualTo(DayBoundary.today());
                });
    }

    @Test
    void allowsSeveralTreasuresInOneDayAndListsThemInOrder() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        String today = DayBoundary.today().toString();

        mockMvc.perform(postTreasure(user, "산책을 다녀왔어요")).andExpect(status().isCreated());
        mockMvc.perform(postTreasure(user, "작은 용기를 냈어요")).andExpect(status().isCreated());

        mockMvc.perform(getTreasures(user).param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treasures.length()").value(2))
                .andExpect(jsonPath("$.treasures[0].content").value("산책을 다녀왔어요"))
                .andExpect(jsonPath("$.treasures[1].content").value("작은 용기를 냈어요"));
    }

    @Test
    void listsOnlyThatDayWithDateAndEverythingNewestFirstWithout() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate yesterday = DayBoundary.today().minusDays(1);
        treasureRepository.saveAndFlush(Treasure.create(user.getId(), yesterday, "어제의 보물"));
        mockMvc.perform(postTreasure(user, "오늘의 보물")).andExpect(status().isCreated());

        mockMvc.perform(getTreasures(user).param("date", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treasures.length()").value(1))
                .andExpect(jsonPath("$.treasures[0].content").value("어제의 보물"))
                .andExpect(jsonPath("$.treasures[0].date").value(yesterday.toString()));

        mockMvc.perform(getTreasures(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treasures.length()").value(2))
                .andExpect(jsonPath("$.treasures[0].content").value("오늘의 보물"))
                .andExpect(jsonPath("$.treasures[1].content").value("어제의 보물"));
    }

    @Test
    void neverShowsAnotherUsersTreasures() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postTreasure(anotherUser, "다른 사람의 보물")).andExpect(status().isCreated());

        mockMvc.perform(getTreasures(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treasures.length()").value(0));
        mockMvc.perform(getTreasures(user).param("date", DayBoundary.today().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treasures.length()").value(0));
    }

    @Test
    void rejectsBlankOrTooLongContentWithoutSaving() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postTreasure(user, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("보물 내용을 입력해주세요."));
        mockMvc.perform(postTreasure(user, "가".repeat(Treasure.CONTENT_MAX_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("보물 내용은 최대 100자입니다."));
        mockMvc.perform(postTreasure(user, "가".repeat(Treasure.CONTENT_MAX_LENGTH)))
                .andExpect(status().isCreated());

        assertThat(treasureRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(getTreasures(user).param("date", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/treasures"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/treasures").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"보물\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(treasureRepository.count()).isZero();
    }

    @Test
    void removesTreasuresWhenUserIsDeleted() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postTreasure(user, "보물")).andExpect(status().isCreated());

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(treasureRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder postTreasure(User user, String content) {
        return post("/api/v1/treasures")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    private MockHttpServletRequestBuilder getTreasures(User user) {
        return get("/api/v1/treasures").header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
