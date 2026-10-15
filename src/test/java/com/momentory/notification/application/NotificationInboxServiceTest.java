package com.momentory.notification.application;

import com.momentory.notification.domain.InboxItem;
import com.momentory.notification.infrastructure.NotificationInboxRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationInboxServiceTest {

    private final NotificationInboxRepository repository = mock(NotificationInboxRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationInboxService service = new NotificationInboxService(repository, users);

    @Test
    void cursorPreservesTimestampAndIdForTiedPublications() {
        Instant published = Instant.parse("2026-10-15T00:00:00Z");
        InboxItem newer = new InboxItem(2L, "NOTICE", "newer", "", null, published, null);
        InboxItem older = new InboxItem(1L, "NOTICE", "older", "", null, published, null);
        User user = User.create();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(repository.list(eq(7L), isNull(), any(), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(newer, older));
        when(repository.list(eq(7L), isNull(), any(), eq(published), eq(2L), eq(2)))
                .thenReturn(List.of(older));

        InboxPage first = service.list(7L, null, 1);
        InboxPage second = service.list(7L, first.nextCursor(), 1);

        assertThat(first.items()).containsExactly(newer);
        assertThat(second.items()).containsExactly(older);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void malformedCursorAndSizeFailBeforeQuery() {
        assertThatThrownBy(() -> service.list(7L, "eHx5", 20))
                .isInstanceOf(NotificationException.class)
                .hasMessage("알림 목록 커서가 올바르지 않습니다.");
        assertThatThrownBy(() -> service.list(7L, null, 51))
                .isInstanceOf(NotificationException.class)
                .hasMessage("잘못된 요청입니다.");
        verifyNoInteractions(repository);
    }
}
