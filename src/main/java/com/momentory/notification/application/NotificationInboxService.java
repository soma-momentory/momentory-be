package com.momentory.notification.application;

import com.momentory.notification.domain.InboxItem;
import com.momentory.notification.infrastructure.NotificationInboxRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class NotificationInboxService {

    private final NotificationInboxRepository inboxRepository;
    private final UserRepository userRepository;

    public NotificationInboxService(NotificationInboxRepository inboxRepository, UserRepository userRepository) {
        this.inboxRepository = inboxRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public InboxPage list(Long userId, String cursor, int size) {
        if (size < 1 || size > 50) {
            throw NotificationException.invalidRequest();
        }
        Cursor position = decode(cursor);
        User user = user(userId);
        List<InboxItem> fetched = inboxRepository.list(userId, user.getCreatedAt(), Instant.now(),
                position == null ? null : position.publishedAt(), position == null ? null : position.id(), size + 1);
        if (fetched.size() <= size) {
            return new InboxPage(fetched, null);
        }
        List<InboxItem> items = List.copyOf(fetched.subList(0, size));
        InboxItem last = items.getLast();
        return new InboxPage(items, encode(new Cursor(last.publishedAt(), last.id())));
    }

    @Transactional(readOnly = true)
    public boolean hasUnread(Long userId) {
        User user = user(userId);
        return inboxRepository.hasUnread(userId, user.getCreatedAt(), Instant.now());
    }

    @Transactional
    public void markRead(Long userId, String id) {
        Long notificationId = parseId(id);
        User user = user(userId);
        Instant now = Instant.now();
        InboxItem item = inboxRepository.findVisible(userId, user.getCreatedAt(), now, notificationId)
                .orElseThrow(NotificationException::notFound);
        if (item.readAt() == null) {
            inboxRepository.markRead(userId, notificationId, now);
        }
    }

    @Transactional
    public void markRead(Long userId, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50) {
            throw NotificationException.invalidRequest();
        }
        List<Long> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(Long.parseLong(id));
            } catch (NumberFormatException exception) {
                throw NotificationException.invalidRequest();
            }
        }
        User user = user(userId);
        Instant now = Instant.now();
        for (Long id : parsed) {
            if (inboxRepository.findVisible(userId, user.getCreatedAt(), now, id)
                    .filter(item -> item.readAt() == null).isPresent()) {
                inboxRepository.markRead(userId, id, now);
            }
        }
    }

    private User user(Long userId) {
        return userRepository.findById(userId).orElseThrow(AuthenticatedUserNotFoundException::new);
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw NotificationException.notFound();
        }
    }

    private String encode(Cursor cursor) {
        String value = cursor.publishedAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.length() > 128) {
            throw NotificationException.invalidCursor();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw NotificationException.invalidCursor();
            }
            Instant publishedAt = Instant.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id < 1 || !encode(new Cursor(publishedAt, id)).equals(cursor)) {
                throw NotificationException.invalidCursor();
            }
            return new Cursor(publishedAt, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw NotificationException.invalidCursor();
        }
    }

    private record Cursor(Instant publishedAt, Long id) {
    }
}
