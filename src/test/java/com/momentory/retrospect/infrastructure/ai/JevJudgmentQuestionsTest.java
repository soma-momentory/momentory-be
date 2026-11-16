package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.momentory.retrospect.domain.judgment.JudgmentItem;

class JevJudgmentQuestionsTest {

    @ParameterizedTest
    @EnumSource(JudgmentItem.class)
    void criteriaKeysMatchTheItemsChoices(JudgmentItem item) {
        assertThat(JevJudgmentQuestions.criteria(item).keySet())
                .containsExactlyElementsOf(item.choices());
        assertThat(JevJudgmentQuestions.criteria(item).values()).allSatisfy(d -> assertThat(d).isNotBlank());
        assertThat(JevJudgmentQuestions.instructions(item)).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(JudgmentItem.class)
    @SuppressWarnings("unchecked")
    void everyItemIsSentAsAChoiceQuestionUnderItsKey(JudgmentItem item) {
        Map<String, Object> question = (Map<String, Object>) JevJudgmentQuestions.all().get(item.key());
        assertThat(question).containsEntry("type", "choice");
        assertThat(JevJudgmentQuestions.all()).hasSize(JudgmentItem.values().length);
    }
}
