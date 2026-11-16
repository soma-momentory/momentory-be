package com.momentory.retrospect.infrastructure.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.TurnJudge;
import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 턴 판단 어댑터 — Jev 한 번 호출로 여섯 항목을 함께 판단한다.
 *
 * <p>키가 없으면 부르지 않는다. 호출이 실패하거나 응답이 선택지 정의와 맞지 않으면 empty 를 돌려준다 —
 * 일부 항목만 채운 판단은 만들지 않는다. 재시도하지 않는다(매 턴 문장 생성 앞에 끼어들기 때문).
 * 대화 내용은 로그에 남기지 않는다.
 */
@Component
public class JevTurnJudge implements TurnJudge {

    private static final Logger log = LoggerFactory.getLogger(JevTurnJudge.class);

    /** 판단에 넘기는 앞선 대화의 최대 메시지 수 — 토큰과 응답 시간을 묶어 둔다. */
    static final int HISTORY_MESSAGES = 8;

    private final RestClient restClient;
    private final JevApiProperties properties;

    public JevTurnJudge(@Qualifier("jevRestClient") RestClient restClient, JevApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public Optional<TurnJudgment> judge(RetrospectState state, String userText) {
        if (!properties.configured()) {
            return Optional.empty();
        }
        long startedAt = System.nanoTime();
        try {
            JevResponse response = restClient.post()
                    .uri("/v1/systemone")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request(state, userText))
                    .retrieve()
                    .body(JevResponse.class);
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            Optional<TurnJudgment> judgment = toJudgment(response, latencyMs);
            if (judgment.isEmpty()) {
                log.warn("jev judgment rejected session={} reason=unexpected-answer", state.id());
            }
            return judgment;
        } catch (RestClientException e) {
            log.warn("jev judgment failed session={} error={}", state.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    Map<String, Object> request(RetrospectState state, String userText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("state", Map.of(
                "대화", history(state),
                "사용자의_마지막_발화", userText.strip()));
        body.put("questions", JevJudgmentQuestions.all());
        return body;
    }

    /** 방금 답변 앞의 대화 — 엔진이 방금 답변을 이미 기록에 넣었으므로 마지막 사용자 발화는 뺀다. */
    private static List<Map<String, String>> history(RetrospectState state) {
        List<Message> messages = new ArrayList<>(state.messages());
        if (!messages.isEmpty() && messages.getLast().isUser()) {
            messages.removeLast();
        }
        List<Map<String, String>> history = new ArrayList<>();
        for (Message m : messages.subList(Math.max(0, messages.size() - HISTORY_MESSAGES), messages.size())) {
            String content = PromptFactory.stripOptionLines(m.content());
            if (!content.isEmpty()) {
                history.add(Map.of("화자", m.isUser() ? "사용자" : "바바", "말", content));
            }
        }
        return history;
    }

    private static Optional<TurnJudgment> toJudgment(JevResponse response, long latencyMs) {
        if (response == null || response.answers() == null) {
            return Optional.empty();
        }
        Map<JudgmentItem, ItemJudgment> items = new EnumMap<>(JudgmentItem.class);
        for (JudgmentItem item : JudgmentItem.values()) {
            JevResponse.Answer answer = response.answers().get(item.key());
            if (answer == null || answer.choice() == null || answer.confidence() == null
                    || !item.choices().contains(answer.choice())) {
                return Optional.empty();
            }
            items.put(item, new ItemJudgment(answer.choice(), answer.confidence(), answer.probabilities()));
        }
        int inputTokens = response.usage() == null || response.usage().inputTokens() == null ? 0
                : response.usage().inputTokens();
        return Optional.of(new TurnJudgment(response.model(), JevJudgmentQuestions.VERSION, items,
                inputTokens, latencyMs));
    }
}
