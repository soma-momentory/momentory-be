package com.momentory.report.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeeklyReportRules;
import com.momentory.retrospect.domain.Emotion;

/**
 * 실제 Gemini 로 주간 리포트 글의 모양과 검사 통과 여부를 본다. {@code GEMINI_API_KEY} 가 있을 때만 돈다.
 * 재료는 지어낸 일기라 실제 사용자 글을 보내지 않는다. 글 품질은 출력된 문장을 사람이 읽어 판단한다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers
class GeminiWeeklyReportWriterRealIntegrationTest {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 25);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired GeminiWeeklyReportWriter writer;

    private static DayStory day(int offset, Emotion emotion, String event, String diary, String words) {
        return new DayStory((long) offset, SUNDAY.plusDays(offset), emotion, event, null, null, false, false, null, diary,
                List.of(words));
    }

    @Test
    void steadyWeek() {
        check("steady", List.of(
                day(0, Emotion.CALM, "친구와 저녁", "오랜만에 친구를 만나 저녁을 먹었다.", "오랜만에 배가 아프게 웃었어요"),
                day(1, Emotion.ANXIOUS, "면접 스터디", "스터디에서 대답이 자꾸 막혔다.", "대답이 막혀서 걱정돼요"),
                day(2, Emotion.ANXIOUS, "면접 스터디", "다시 스터디에 갔는데 조금 나았다.", "그래도 다시 갔어요"),
                day(5, Emotion.PROUD, "발표", "발표를 끝냈다. 어깨가 가벼웠다.", "끝나고 나니 후련했어요")));
    }

    @Test
    void hardWeek() {
        check("hard", List.of(
                day(1, Emotion.FRUSTRATED, "회의", "회의가 길어져서 지쳤다.", "회의가 너무 길었어요"),
                day(3, Emotion.TIRED, "마감", "마감 때문에 몸이 무거워서 일찍 잤다.", "몸이 무거워서 일찍 잤어요"),
                day(5, Emotion.STUCK, "야근", "야근하고 돌아왔다. 아무 말도 하기 싫었다.", "아무 말도 하기 싫었어요")));
    }

    @Test
    void shortWeek() {
        check("short", List.of(day(3, Emotion.ANXIOUS, "발표 준비", "발표 준비를 했다.", "그래도 소리 내어 한 번 연습해 봤어요")));
    }

    private void check(String name, List<DayStory> stories) {
        Optional<GeminiWeeklyReportWriter.Draft> draft = writer.write(0L, SUNDAY.toString(), "하늘", stories);
        assertThat(draft).isPresent();
        System.out.println("[weekly " + name + "] " + draft.get());
        assertThat(WeeklyReportRules.checked(draft.get().coverLine(), WeeklyReportRules.COVER_LINE_MAX_LENGTH)).isNotNull();
    }
}
