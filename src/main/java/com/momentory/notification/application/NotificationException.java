package com.momentory.notification.application;

public class NotificationException extends RuntimeException {

    private final String code;

    private NotificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static NotificationException invalidCursor() {
        return new NotificationException("INVALID_CURSOR", "알림 목록 커서가 올바르지 않습니다.");
    }

    public static NotificationException invalidRequest() {
        return new NotificationException("INVALID_REQUEST", "잘못된 요청입니다.");
    }

    public static NotificationException notFound() {
        return new NotificationException("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.");
    }

    public String code() {
        return code;
    }
}
