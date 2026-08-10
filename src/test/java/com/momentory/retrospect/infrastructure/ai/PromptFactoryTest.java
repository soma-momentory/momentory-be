package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.retrospect.domain.script.ScriptStep;
import com.momentory.retrospect.domain.script.Scripts;

/** G2 대화 이력 글자 예산 윈도잉 검증 — 토큰 절감 기법의 동작을 고정한다. */
class PromptFactoryTest {

    /** intro 답변 + 상황 요약 후 스크립트 여러 턴을 태운, 원문이 길게 쌓인 상태. */
    private RetrospectState longConversation() {
        RetrospectState state = new RetrospectState("s1");
        state.begin("면접 스터디", Emotion.ANXIOUS, Emotion.DEPRESSED, "정민");
        state.addAssistantMessage("첫 질문입니다.");
        state.addUserMessage("첫 답변: 모의 면접에서 답변을 제대로 못 했어요.");
        state.situationSummary("모의 면접에서 답변을 제대로 하지 못함");
        state.applyMode(RetroMode.REFRAME);
        for (int i = 1; i <= 6; i++) {
            state.addAssistantMessage("상담사 질문 " + i + " 입니다. 조금 더 자세히 들려주세요.");
            state.addUserMessage("중간 답변 " + i + " 입니다. 그때 이런저런 일이 있었어요.");
        }
        state.addAssistantMessage("마지막 상담사 질문입니다.");
        state.addUserMessage("가장 최근 답변입니다.");
        return state;
    }

    private ScriptStep anyTextStep() {
        return Scripts.stepsOf(RetroMode.REFRAME).stream()
                .filter(s -> !s.isMeasure() && !s.isChoice())
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("글자 예산 안에서 최근 대화만 싣고 오래된 원문은 뺀다 — 최근 답변·상황 요약은 유지")
    void windowsByCharBudget() {
        PromptFactory factory = new PromptFactory(200);
        String prompt = factory.turnPrompt(longConversation(), anyTextStep());

        assertThat(prompt)
                .as("가장 최근 답변은 반드시 남는다(공감 되비추기용)")
                .contains("가장 최근 답변입니다.");
        assertThat(prompt)
                .as("초반 맥락은 상황 요약으로 이월된다")
                .contains("상황 요약: 모의 면접에서 답변을 제대로 하지 못함");
        assertThat(prompt)
                .as("오래된 원문(첫 답변·초반 중간 답변)은 예산 밖이라 빠진다")
                .doesNotContain("첫 답변: 모의 면접에서 답변을 제대로 못 했어요.")
                .doesNotContain("중간 답변 1 입니다");
        assertThat(prompt).contains("앞선 대화는 위 '상황 요약'으로 갈음");
    }

    @Test
    @DisplayName("개수가 아니라 글자로 잰다 — 짧은 메시지는 여러 개, 장문은 적게 들어간다")
    void budgetIsCharsNotCount() {
        // 예산 300자. 짧은 메시지들로 채우면 여러 개가 들어간다.
        RetrospectState shortMsgs = new RetrospectState("a");
        shortMsgs.begin("산책", Emotion.CALM, Emotion.TIRED, null);
        for (int i = 1; i <= 8; i++) {
            shortMsgs.addAssistantMessage("Q" + i);
            shortMsgs.addUserMessage("A" + i);
        }
        String p1 = new PromptFactory(300).turnPrompt(shortMsgs, anyTextStep());

        // 같은 예산인데 한 메시지가 장문이면 그 하나로 예산이 거의 다 찬다.
        RetrospectState longMsg = new RetrospectState("b");
        longMsg.begin("산책", Emotion.CALM, Emotion.TIRED, null);
        longMsg.addAssistantMessage("Q_old");
        longMsg.addUserMessage("이건 아주 긴 답변입니다. ".repeat(20)); // ~260자
        longMsg.addAssistantMessage("Q_recent");
        longMsg.addUserMessage("최근 짧은 답변");
        String p2 = new PromptFactory(300).turnPrompt(longMsg, anyTextStep());

        // 짧은 메시지 세트는 초반 것까지 여럿 들어간다.
        assertThat(p1).contains("A8").contains("A6");
        // 장문 세트는 최근 것만 남고 직전 장문은 예산에 밀려 빠진다.
        assertThat(p2).contains("최근 짧은 답변");
        assertThat(p2).doesNotContain("Q_old");
    }

    @Test
    @DisplayName("예산을 넘는 장문이 가장 최근이어도 그 하나는 무조건 싣는다(바닥)")
    void mostRecentAlwaysIncluded() {
        RetrospectState state = new RetrospectState("c");
        state.begin("산책", Emotion.CALM, Emotion.TIRED, null);
        state.addAssistantMessage("질문");
        String hugeAnswer = "정말 긴 답변이에요. ".repeat(30); // 예산보다 큼
        state.addUserMessage(hugeAnswer);

        String prompt = new PromptFactory(100).turnPrompt(state, anyTextStep());
        assertThat(prompt).contains("정말 긴 답변이에요.");
    }

    @Test
    @DisplayName("historyCharBudget <= 0 이면 무제한 — 전체 대화가 실린다(baseline)")
    void unlimitedIsBaseline() {
        PromptFactory factory = new PromptFactory(0);
        String prompt = factory.turnPrompt(longConversation(), anyTextStep());

        assertThat(prompt)
                .contains("첫 답변: 모의 면접에서 답변을 제대로 못 했어요.")
                .contains("중간 답변 1 입니다")
                .contains("가장 최근 답변입니다.");
        assertThat(prompt).doesNotContain("갈음");
    }
}
