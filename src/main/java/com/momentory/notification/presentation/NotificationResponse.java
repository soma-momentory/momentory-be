package com.momentory.notification.presentation;

import com.momentory.notification.domain.InboxItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record NotificationResponse(
        String id,
        String type,
        String title,
        String body,
        Instant createdAt,
        @Schema(nullable = true) Instant readAt,
        @Schema(nullable = true) String link
) {
    static NotificationResponse from(InboxItem item) {
        return new NotificationResponse(item.id().toString(), item.type(), item.title(), item.body(),
                item.publishedAt(), item.readAt(), item.link());
    }
}
