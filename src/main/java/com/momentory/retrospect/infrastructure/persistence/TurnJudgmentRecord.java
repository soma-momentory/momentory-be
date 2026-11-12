package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 사용자 답변 하나에 대한 판단 기록 한 줄. 발화 원문은 담지 않는다 — {@code messageId} 로 회고 상태의
 * 메시지를 가리킬 뿐이다. 한 번 쓰면 고치지 않는다.
 *
 * <p>항목별 선택과 확신은 집계하기 쉽게 칸으로 풀고, 선택지 확률 전체는 {@code probabilitiesJson} 에 둔다.
 * 판단 호출이 실패한 턴은 {@code status = FAILED} 이고 판단 칸이 모두 비어 있다. 전략 칸은 전략 선택을 켠
 * 계정의 턴에 채워진다 — 판단이 실패한 턴에도 지금까지의 신호로 고른 기본 전략이 남는다.
 */
@Entity
@Table(name = "retrospect_turn_judgments")
public class TurnJudgmentRecord {

    static final String JUDGED = "JUDGED";
    static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "retrospect_id", nullable = false)
    private Long retrospectId;

    @Column(name = "message_id", nullable = false, length = 20)
    private String messageId;

    @Column(name = "diary_turn", nullable = false)
    private Integer diaryTurn;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(length = 50)
    private String model;

    @Column(name = "questions_version", length = 20)
    private String questionsVersion;

    @Column(name = "end_intent", length = 40)
    private String endIntent;

    @Column(name = "end_intent_confidence")
    private Double endIntentConfidence;

    @Column(name = "requested_mode", length = 40)
    private String requestedMode;

    @Column(name = "requested_mode_confidence")
    private Double requestedModeConfidence;

    @Column(name = "emotion_expression", length = 40)
    private String emotionExpression;

    @Column(name = "emotion_expression_confidence")
    private Double emotionExpressionConfidence;

    @Column(name = "event_info", length = 40)
    private String eventInfo;

    @Column(name = "event_info_confidence")
    private Double eventInfoConfidence;

    @Column(length = 40)
    private String reaction;

    @Column(name = "reaction_confidence")
    private Double reactionConfidence;

    @Column(name = "experiencing_depth", length = 40)
    private String experiencingDepth;

    @Column(name = "experiencing_depth_confidence")
    private Double experiencingDepthConfidence;

    @Column(name = "probabilities_json", columnDefinition = "TEXT")
    private String probabilitiesJson;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "strategy_type", length = 20)
    private String strategyType;

    @Column(name = "question_focus", length = 20)
    private String questionFocus;

    @Column(name = "question_as_choices")
    private Boolean questionAsChoices;

    @Column(name = "policy_version", length = 20)
    private String policyVersion;

    @Column(name = "strategy_applied")
    private Boolean strategyApplied;

    @Column(name = "generation_failed", nullable = false)
    private boolean generationFailed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TurnJudgmentRecord() {
    }

    /**
     * @param probabilitiesJson 항목 키별 선택지 확률(JSON). 실패한 턴이면 null.
     */
    public static TurnJudgmentRecord of(Long userId, Long retrospectId, TurnJudgmentLog log,
            String probabilitiesJson) {
        TurnJudgmentRecord record = new TurnJudgmentRecord();
        record.userId = Objects.requireNonNull(userId, "userId must not be null");
        record.retrospectId = Objects.requireNonNull(retrospectId, "retrospectId must not be null");
        record.messageId = Objects.requireNonNull(log.messageId(), "messageId must not be null");
        record.diaryTurn = log.diaryTurn();
        record.createdAt = Instant.now();
        record.generationFailed = log.generationFailed();
        if (log.strategy() != null) {
            record.strategyType = log.strategy().type().name();
            record.questionFocus = log.strategy().focus().name();
            record.questionAsChoices = log.strategy().asChoices();
            record.policyVersion = log.policyVersion();
            record.strategyApplied = log.strategyApplied();
        }
        if (log.failed()) {
            record.status = FAILED;
            return record;
        }
        TurnJudgment j = log.judgment();
        record.status = JUDGED;
        record.model = j.model();
        record.questionsVersion = j.questionsVersion();
        ItemJudgment endIntent = j.of(JudgmentItem.END_INTENT);
        record.endIntent = endIntent.choice();
        record.endIntentConfidence = endIntent.confidence();
        ItemJudgment requestedMode = j.of(JudgmentItem.REQUESTED_MODE);
        record.requestedMode = requestedMode.choice();
        record.requestedModeConfidence = requestedMode.confidence();
        ItemJudgment emotionExpression = j.of(JudgmentItem.EMOTION_EXPRESSION);
        record.emotionExpression = emotionExpression.choice();
        record.emotionExpressionConfidence = emotionExpression.confidence();
        ItemJudgment eventInfo = j.of(JudgmentItem.EVENT_INFO);
        record.eventInfo = eventInfo.choice();
        record.eventInfoConfidence = eventInfo.confidence();
        ItemJudgment reaction = j.of(JudgmentItem.REACTION);
        record.reaction = reaction.choice();
        record.reactionConfidence = reaction.confidence();
        ItemJudgment experiencingDepth = j.of(JudgmentItem.EXPERIENCING_DEPTH);
        record.experiencingDepth = experiencingDepth.choice();
        record.experiencingDepthConfidence = experiencingDepth.confidence();
        record.probabilitiesJson = Objects.requireNonNull(probabilitiesJson,
                "probabilitiesJson must not be null");
        record.inputTokens = j.inputTokens();
        record.latencyMs = (int) Math.min(j.latencyMs(), Integer.MAX_VALUE);
        return record;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getRetrospectId() { return retrospectId; }
    public String getMessageId() { return messageId; }
    public Integer getDiaryTurn() { return diaryTurn; }
    public String getStatus() { return status; }
    public String getModel() { return model; }
    public String getQuestionsVersion() { return questionsVersion; }
    public String getEndIntent() { return endIntent; }
    public Double getEndIntentConfidence() { return endIntentConfidence; }
    public String getRequestedMode() { return requestedMode; }
    public String getEmotionExpression() { return emotionExpression; }
    public String getEventInfo() { return eventInfo; }
    public String getReaction() { return reaction; }
    public String getExperiencingDepth() { return experiencingDepth; }
    public String getProbabilitiesJson() { return probabilitiesJson; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getLatencyMs() { return latencyMs; }
    public String getStrategyType() { return strategyType; }
    public String getQuestionFocus() { return questionFocus; }
    public Boolean getQuestionAsChoices() { return questionAsChoices; }
    public String getPolicyVersion() { return policyVersion; }
    public Boolean getStrategyApplied() { return strategyApplied; }
    public boolean isGenerationFailed() { return generationFailed; }
}
