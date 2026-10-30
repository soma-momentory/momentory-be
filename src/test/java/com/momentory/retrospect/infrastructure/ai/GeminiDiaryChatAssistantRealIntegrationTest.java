package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.momentory.retrospect.domain.RetrospectState;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/** 공개된 예시 문장만 보내는 선택 실행 검사. 사용자 일기나 인증 정보를 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiDiaryChatAssistantRealIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"아직 안 만났어.", "약속은 이따 저녁이에요. 아직 만나기 전이에요."})
    void pendingMeetingIsRecognizedAsTimeCorrection(String answer) {
        var state = new RetrospectState("pending-meeting-smoke");
        state.begin("유나 약속", null, null, "민지", null);
        state.addAssistantMessage("민지님, 오늘 유나 약속, 어땠어요?");

        var reply = assistant().turn(state, answer).orElseThrow();

        assertThat(reply.eventPending()).isTrue();
        assertThat(reply.offTopic()).isFalse();
        assertThat(reply.vague()).isFalse();
        assertThat(reply.empathy()).isNotBlank().doesNotContain("?", "？");
    }

    @Test
    void cafePlanChangeStaysOnTheSameExperience() {
        var state = new RetrospectState("cafe-feeling-smoke");
        state.begin("유나 약속", null, null, "민지", null);
        state.addAssistantMessage("아직 만나기 전이시군요.\n그럼 그전에는 어떤 일을 하셨어요?");

        var reply = assistant().turn(state, "카페 가서 공부하려고 했는데 귀찮아서 안 나갔어.").orElseThrow();

        assertThat(reply.eventPending()).isFalse();
        assertThat(reply.empathy()).isNotBlank().containsAnyOf("카페", "집", "외출", "나가", "나오");
        assertThat(reply.question()).doesNotContain("다른 일", "다른 기억", "의미", "생각");
        if (reply.emotionPresent() && reply.emotionCausePresent()) {
            assertThat(reply.question()).isEmpty();
        } else if (reply.emotionPresent()) {
            assertThat(reply.question()).containsAnyOf("때문", "계기", "어떤 일", "어떤 부분", "어떤 순간", "어느 순간", "어떤 상황", "어떤 점", "어느 점", "어떨 때", "무엇", "왜", "뭐가");
        } else {
            assertThat(reply.question()).containsAnyOf("기분", "마음", "감정", "느끼", "느꼈");
        }
    }

    @Test
    void pendingMeetingAlongsidePastWalkUsesActualExperience() {
        var state = new RetrospectState("mixed-past-smoke");
        state.begin("유나 약속", null, null, "민지", null);
        state.addAssistantMessage("민지님, 오늘 유나 약속, 어땠어요?");

        var reply = assistant().turn(state, "아직 안 만났고, 오전에는 공원에서 산책했어요.").orElseThrow();

        assertThat(reply.eventPending()).isFalse();
        assertThat(reply.event()).containsAnyOf("산책", "공원");
    }

    @Test
    void firstReplyHasGroundedEmpathyInsteadOfBareAcknowledgement() {
        var state = new RetrospectState("grounded-empathy-smoke");
        state.begin(null, null, null, "정민", null);

        var reply = assistant().turn(state, "오랜만에 만난 친구가 제 생일을 챙겨줬어요. 많이 고마웠어요.");

        assertThat(reply).isPresent();
        assertThat(reply.orElseThrow().empathy()).isNotBlank()
                .containsAnyOf("친구", "생일", "고마", "고맙", "감사")
                .doesNotContain("?", "？")
                .isNotEqualTo("그랬군요.");
    }

    @Test
    void cancelledPlanIsAnActualExperienceRatherThanPending() {
        var state = new RetrospectState("cancelled-plan-smoke");
        state.begin("저녁 약속", null, null, "정민", null);
        state.addAssistantMessage("오늘 저녁 약속은 어땠어요?");

        var reply = assistant().turn(state, "약속이 취소돼서 못 만났어요.").orElseThrow();

        assertThat(reply.eventPending()).isFalse();
        assertThat(reply.offTopic()).isFalse();
        assertThat(reply.vague()).isFalse();
        assertThat(reply.question()).containsAnyOf("기분", "마음", "감정", "느끼", "느꼈");
    }

    @Test
    void emotionWithoutCauseGetsOneCauseQuestion() {
        var state = new RetrospectState("emotion-followup-smoke");
        state.event("공원 산책");
        state.bumpDiaryTurn();
        state.addUserMessage("퇴근하고 공원에서 산책했어요.");
        state.addAssistantMessage("잠깐 공원을 걸으셨군요. 그때 마음은 어떠셨어요?");

        var reply = assistant().turn(state, "한결 편안했어요.").orElseThrow();

        assertThat(reply.emotionPresent()).isTrue();
        assertThat(reply.eventPending()).isFalse();
        assertThat(reply.emotionCausePresent()).isFalse();
        assertThat(reply.question()).containsAnyOf("때문", "계기", "어떤 일", "어떤 부분", "어떤 순간", "어느 순간", "어떤 상황", "어떤 점", "어느 점", "어떨 때", "무엇", "왜", "뭐가")
                .doesNotContain("다른 일", "의미", "생각");
    }

    @ParameterizedTest
    @ValueSource(strings = {"퇴근길에 버스 놓침ㅠㅠ", "퇴근길에 버스를 놓쳐서 한참 기다렸습니다.", "버스... 놓쳤네여 한참 기다렷어"})
    void differentSpeakingStylesGetContextualEmpathyAndAFeelingQuestion(String answer) {
        var state = new RetrospectState("speaking-style-smoke");
        state.addAssistantMessage("오늘 기억에 남는 일은 무엇이었어요?");

        var reply = assistant().turn(state, answer).orElseThrow();

        assertThat(reply.eventPending()).isFalse();
        assertThat(reply.offTopic()).isFalse();
        assertThat(reply.vague()).isFalse();
        assertThat(reply.event()).contains("버스");
        assertThat(reply.empathy()).isNotBlank().doesNotContain("ㅠ", "네여");
        if (reply.emotionCausePresent() && reply.emotionPresent()) {
            assertThat(reply.question()).isEmpty();
        } else if (reply.emotionPresent()) {
            assertThat(reply.question()).containsAnyOf("때문", "계기", "어떤 일", "어떤 부분", "어떤 순간", "어느 순간", "어떤 상황", "어떤 점", "어느 점", "어떨 때", "무엇", "왜", "뭐가");
        } else {
            assertThat(reply.question()).containsAnyOf("기분", "마음", "감정", "느끼", "느꼈");
        }
    }

    @Test
    void feelingAndExplicitCauseNeedOnlyEmpathy() {
        var state = new RetrospectState("emotion-with-cause-smoke");
        var reply = assistant().turn(state, "친구가 제 발표를 칭찬해줘서 정말 기뻤어요.").orElseThrow();
        assertThat(reply.emotionPresent()).isTrue();
        assertThat(reply.emotionCausePresent()).isTrue();
        assertThat(reply.empathy()).isNotBlank();
        assertThat(reply.question()).isEmpty();
    }

    @Test
    void ordinaryExperienceWithoutEmotionGetsFeelingQuestion() {
        var state = new RetrospectState("experience-no-emotion-smoke");
        var reply = assistant().turn(state, "오전에 도서관에서 책을 한 시간 읽었어요.").orElseThrow();
        assertThat(reply.emotionPresent()).isFalse();
        assertThat(reply.emotionCausePresent()).isFalse();
        assertThat(reply.question()).containsAnyOf("기분", "마음", "감정", "느끼", "느꼈");
    }

    private static GeminiDiaryChatAssistant assistant() {
        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var restClient = new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP);
        var client = new GeminiApiClient(restClient, JsonMapper.builder().build(), event -> { }, properties);
        return new GeminiDiaryChatAssistant(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));
    }
}
