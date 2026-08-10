package com.momentory.retrospect.domain;

/**
 * 대화 메시지 하나 — 값 객체 (원본 state.py {@code Message}).
 *
 * @param questionType context | reframing | action (없으면 null)
 */
public record Message(String id, String role, String content, String questionType) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }
}
