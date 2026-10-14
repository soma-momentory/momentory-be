package com.momentory.inquiry.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.inquiry.domain.InquiryCategory;
import com.momentory.inquiry.domain.InquiryPlatform;
import com.momentory.inquiry.infrastructure.InquiryRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class InquiryControllerIntegrationTest {

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
    @Autowired InquiryRepository inquiryRepository;
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
        inquiryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void savesInquiryWithDeviceInfoAsReceived() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postInquiry(user, """
                        {"category":"BUG","content":"  일기를 저장하면 앱이 꺼져요.  ","replyEmail":" me@example.com ",
                         "platform":"IOS","osVersion":"18.6","appVersion":"1.2.0"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(inquiryRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getCategory()).isEqualTo(InquiryCategory.BUG);
                    assertThat(saved.getContent()).isEqualTo("일기를 저장하면 앱이 꺼져요.");
                    assertThat(saved.getReplyEmail()).isEqualTo("me@example.com");
                    assertThat(saved.getPlatform()).isEqualTo(InquiryPlatform.IOS);
                    assertThat(saved.getOsVersion()).isEqualTo("18.6");
                    assertThat(saved.getAppVersion()).isEqualTo("1.2.0");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inquiries", String.class)).isEqualTo("RECEIVED");
    }

    @Test
    void storesBlankReplyEmailAsNull() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postInquiry(user, "{\"category\":\"SUGGESTION\",\"content\":\"다크 모드가 있으면 좋겠어요.\",\"replyEmail\":\"  \"}"))
                .andExpect(status().isCreated());

        assertThat(inquiryRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getReplyEmail()).isNull();
                    assertThat(saved.getPlatform()).isNull();
                });
    }

    @Test
    void rejectsInvalidInquiryWithoutSaving() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postInquiry(user, "{\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("문의 종류를 골라주세요."));
        mockMvc.perform(postInquiry(user, "{\"category\":\"ETC\",\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("문의 내용을 입력해주세요."));
        mockMvc.perform(postInquiry(user, "{\"category\":\"ETC\",\"content\":\"" + "가".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("문의 내용은 최대 2000자입니다."));
        mockMvc.perform(postInquiry(user, "{\"category\":\"ETC\",\"content\":\"내용\",\"replyEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이메일 형식이 올바르지 않습니다."));
        mockMvc.perform(postInquiry(user, "{\"category\":\"ETC\",\"content\":\"내용\",\"appVersion\":\"" + "1".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("기기 정보는 최대 30자입니다."));
        mockMvc.perform(postInquiry(user, "{\"category\":\"NOT_A_CATEGORY\",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));

        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    void acceptsContentOfExactlyMaxLength() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postInquiry(user, "{\"category\":\"ETC\",\"content\":\"" + "가".repeat(2000) + "\"}"))
                .andExpect(status().isCreated());

        assertThat(inquiryRepository.count()).isEqualTo(1);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"ETC\",\"content\":\"내용\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    void removesInquiriesWhenUserIsDeleted() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postInquiry(user, "{\"category\":\"ACCOUNT\",\"content\":\"탈퇴하고 싶어요.\"}")).andExpect(status().isCreated());

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(inquiryRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder postInquiry(User user, String body) {
        return post("/api/v1/inquiries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
