package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.StrategyReply;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전략별 문장 생성을 고정 발화 묶음으로 돌려 출력하는 선택 실행 검사 — 프롬프트나 모델을 바꿀 때 문장 오류
 * (행위자 뒤바뀜 · 말하지 않은 감정 · 화자 혼동)를 사람이 읽어 세는 데 쓴다. 지어낸 예시 문장만 보낸다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STRATEGY_WORDING_CHECK", matches = "true")
class StrategyWordingRealIntegrationTest {

    private record Case(String baba, String user, StrategyType type, QuestionFocus focus, String reaction) {

        Case(String baba, String user, StrategyType type, QuestionFocus focus) {
            this(baba, user, type, focus, null);
        }
    }

    private static Case reflect(String baba, String user) {
        return new Case(baba, user, StrategyType.REFLECT, QuestionFocus.NONE);
    }

    private static Case explore(String baba, String user, QuestionFocus focus) {
        return new Case(baba, user, StrategyType.EXPLORE, QuestionFocus.OPEN);
    }

    private static final String OPEN = "오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?";
    private static final String FEEL = "그때 어떤 기분이 드셨나요?";

    private static final List<Case> CASES = List.of(
            // 행위자가 둘 이상 — 누가 무엇을 했는지 뒤바꾸기 쉽다.
            reflect(FEEL, "걔가 먼저 약속을 깼는데 내가 예민하다는 거야."),
            reflect(FEEL, "동생이 내 옷을 말도 없이 입고 나갔는데 엄마는 나보고 참으래."),
            reflect(FEEL, "내가 먼저 사과했는데 걔는 아무 말도 안 하더라."),
            reflect(OPEN, "팀원이 자료를 안 보내줘서 내가 밤새 다 만들었어. 힘들고 화났어."),
            reflect(FEEL, "친구가 나한테 빌린 돈을 안 갚았는데 내가 달라고 하니까 쪼잔하대."),
            reflect(FEEL, "선배가 내 아이디어를 자기 거처럼 발표했어. 너무 허탈했어."),
            reflect(OPEN, "엄마가 전화했는데 내가 바빠서 못 받았어. 미안하더라."),
            reflect(FEEL, "남자친구가 약속 시간에 한 시간 늦었는데 내가 화내니까 오히려 짜증을 냈어."),
            // 감정을 말하지 않음 — 감정 이름을 붙이면 안 된다.
            reflect(OPEN, "오늘 회의가 세 개나 있었어."),
            reflect(OPEN, "점심에 혼자 김밥 먹었어."),
            reflect(OPEN, "교수님이 과제 다시 해 오래."),
            reflect(OPEN, "면접 결과가 아직 안 나왔어."),
            explore(OPEN, "오늘 팀장한테 또 한 소리 들었어.", QuestionFocus.SELF),
            explore(OPEN, "친구가 갑자기 이사 간대.", QuestionFocus.SELF),
            explore(OPEN, "시험 범위가 또 늘어났어.", QuestionFocus.SELF),
            explore(OPEN, "오늘 발표가 있었어.", QuestionFocus.EVENT),
            // 듣기만 원함 · 질문 거부.
            reflect("어떤 일 때문에 다투게 되셨나요?", "질문 말고 그냥 들어줘."),
            reflect(FEEL, "그냥 말하고 싶었어. 뭘 물어보진 마."),
            reflect("어떤 일이 있었는지 조금 더 들려주실래요?", "별로 설명하고 싶지 않아. 그냥 힘든 하루였어."),
            // 해석 수정.
            new Case("많이 화가 나셨겠어요.", "화난 게 아니라 서운한 거야.", StrategyType.YIELD, QuestionFocus.OPEN, "correct_interpretation"),
            new Case("많이 불안하셨군요.", "불안한 건 아니고 그냥 귀찮았어.", StrategyType.YIELD, QuestionFocus.OPEN, "correct_interpretation"),
            new Case("슬프셨겠어요.", "슬프다기보단 좀 허무했어.", StrategyType.YIELD, QuestionFocus.OPEN, "correct_interpretation"),
            // 감정을 말함 — 한 단계 깊게.
            explore(FEEL, "지적받아서 속상했어.", QuestionFocus.FEELING),
            explore(FEEL, "발표 망쳐서 허무해.", QuestionFocus.FEELING),
            explore("그 속상함은 어떤 느낌이었나요?", "요즘 이런 말에 유독 크게 흔들리는 것 같아.", QuestionFocus.MEANING),
            explore(FEEL, "합격해서 기뻤는데 한편으론 좀 무서웠어.", QuestionFocus.MEANING),
            // 스스로 탐색 중 · 짧은 답.
            reflect("그 속상함은 어떤 느낌이었나요?", "속상한 게 지적 자체보다, 인정받고 싶었는데 못 받아서인 것 같아."),
            reflect("그 마음은 어디에서 온 것 같으세요?", "생각해 보니 걔한테 화난 게 아니라 나한테 실망한 거였어."),
            reflect(FEEL, "응."),
            // 재해석.
            new Case(FEEL, "내가 너무 못나서 그런 것 같아. 내가 잘못 생각하는 건가?", StrategyType.REFRAME,
                    QuestionFocus.OPEN));

    @Test
    void printsTheWordingForEveryCase() throws InterruptedException {
        var properties = new GeminiApiProperties("https://generativelanguage.googleapis.com",
                System.getenv("GEMINI_API_KEY"), "gemini-flash-lite-latest", 0.7,
                Duration.ofSeconds(3), Duration.ofSeconds(8), "gemini-embedding-001");
        var client = new GeminiApiClient(
                new GeminiApiClientConfiguration().geminiRestClient(properties, ObservationRegistry.NOOP),
                JsonMapper.builder().build(), event -> { }, properties);
        var writer = new GeminiStrategyReplyWriter(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));

        int answered = 0;
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            RetrospectState state = new RetrospectState("wording-" + i);
            state.begin(null, null, null, "민지", null);
            state.addAssistantMessage(c.baba());
            state.addUserMessage(c.user());
            TurnStrategy strategy = new TurnStrategy(c.type(), c.focus(), false, c.reaction());
            Optional<StrategyReply> turn = Optional.empty();
            for (int attempt = 0; attempt < 3 && turn.isEmpty(); attempt++) {
                turn = writer.write(state, c.user(), strategy);
                if (turn.isEmpty()) {
                    Thread.sleep(3000);
                }
            }
            answered += turn.isPresent() ? 1 : 0;
            System.out.printf("WORDING %02d [%s/%s] 나: %s%n           받음: %s%n           질문: %s%n"
                            + "           새 이야기=%s 더 물을 것 없음=%s%n", i + 1,
                    c.type(), c.focus(), c.user(), turn.map(StrategyReply::ack).orElse("(실패)"),
                    turn.map(StrategyReply::question).orElse("(실패)"),
                    turn.map(r -> String.valueOf(r.newTopic())).orElse("-"),
                    turn.map(r -> String.valueOf(r.nothingMoreToAsk())).orElse("-"));
        }
        assertThat(answered).isGreaterThanOrEqualTo(CASES.size() - 3);
    }
}
