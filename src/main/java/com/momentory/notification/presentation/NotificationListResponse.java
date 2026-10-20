package com.momentory.notification.presentation;

import com.momentory.notification.application.InboxPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record NotificationListResponse(List<NotificationResponse> items,
                                       @Schema(nullable = true) String nextCursor) {
    static NotificationListResponse from(InboxPage page) {
        return new NotificationListResponse(page.items().stream().map(NotificationResponse::from).toList(),
                page.nextCursor());
    }
}
