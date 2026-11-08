package com.momentory.notification.domain;

import java.time.Instant;

public record InboxItem(Long id, String type, String title, String body, String link,
                        Instant publishedAt, Instant readAt) {
}
