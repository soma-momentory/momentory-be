package com.momentory.notification.application;

import com.momentory.notification.domain.InboxItem;

import java.util.List;

public record InboxPage(List<InboxItem> items, String nextCursor) {
}
