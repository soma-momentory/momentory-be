package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 일기 생성 모델의 구조화 출력 — 문단(사건·맥락)마다 문장 목록.
 *
 * <p>줄바꿈을 모델에게 맡기지 않는다. 모델에게는 "어느 문장이 같은 사건인가"만 묻고, 줄바꿈은 여기서 만든다 —
 * 같은 문단의 문장은 바로 다음 줄에, 문단 사이에만 빈 줄 하나. 글자로 줄바꿈을 지시했을 때는 문장마다 빈 줄을
 * 넣은 일기가 나왔다.
 */
record DiaryDraft(List<Correction> corrections, List<Paragraph> paragraphs) {

    /**
     * 사용자가 자기 말을 고쳐 말한 곳. 모델이 문단을 쓰기 전에 먼저 짚게 해서, 일기에는 고친 뒤의 말만 남긴다.
     *
     * @param before 고치기 전 표현(오타·말실수)
     * @param after  고친 표현
     */
    record Correction(String before, String after) {

        /** 고치기 전 표현이 문장에 그대로 남았는가 — 고친 표현 안에 들어 있는 말이면 가려낼 수 없어 보지 않는다. */
        boolean leftIn(String sentence) {
            if (before == null || before.strip().length() < 2) {
                return false;
            }
            String wrong = before.strip();
            return (after == null || !after.contains(wrong)) && sentence.contains(wrong);
        }
    }

    DiaryDraft(List<Paragraph> paragraphs) {
        this(List.of(), paragraphs);
    }

    /**
     * @param event     이 문단이 다루는 일 한 줄. 본문에는 쓰지 않는다 — 모델이 서로 다른 일을 먼저 구분하게 하려고 받는다.
     * @param sentences 그 일에 대한 문장들
     */
    record Paragraph(String event, List<String> sentences) {

        Paragraph(List<String> sentences) {
            this(null, sentences);
        }

        List<String> cleaned() {
            if (sentences == null) {
                return List.of();
            }
            return sentences.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.strip().replaceAll("\\s+", " "))
                    .toList();
        }
    }

    private List<List<String>> cleaned() {
        if (paragraphs == null) {
            return List.of();
        }
        return paragraphs.stream()
                .filter(p -> p != null)
                .map(Paragraph::cleaned)
                // 고치기 전 표현이 그대로 남은 문장은 뺀다 — 지시를 어긴 문장이 일기에 남지 않게 하는 마지막 그물이다.
                .map(sentences -> sentences.stream().filter(s -> !mentionsCorrectedWord(s)).toList())
                .filter(sentences -> !sentences.isEmpty())
                .toList();
    }

    private boolean mentionsCorrectedWord(String sentence) {
        return corrections != null && corrections.stream()
                .anyMatch(c -> c != null && c.leftIn(sentence));
    }

    boolean hasText() {
        return !cleaned().isEmpty();
    }

    int sentenceCount() {
        return cleaned().stream().mapToInt(List::size).sum();
    }

    /** 일기 본문 — 문장마다 줄바꿈, 문단 사이에만 빈 줄 하나. */
    String text() {
        return cleaned().stream()
                .map(sentences -> String.join("\n", sentences))
                .collect(Collectors.joining("\n\n"));
    }
}
