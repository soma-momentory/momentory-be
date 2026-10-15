package com.momentory.notification.infrastructure;

import com.momentory.notification.domain.InboxItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationInboxRepository {

    private static final String VISIBLE = """
            n.published_at <= :now
            AND (n.audience = 'ALL' OR (n.audience = 'USER' AND n.user_id = :userId))
            AND ((n.type = 'CRITICAL_NOTICE' AND n.expires_at > :now)
                 OR (n.type <> 'CRITICAL_NOTICE' AND n.published_at >= :joinedAt
                     AND n.published_at > :cutoff
                     AND (n.expires_at IS NULL OR n.expires_at > :now)))
            """;

    private static final String ITEM_COLUMNS = """
            n.id, n.type, n.title, n.body, n.link, n.published_at,
            CASE WHEN n.type = 'CRITICAL_NOTICE' AND n.published_at < :joinedAt
                THEN CAST(:joinedAt AS timestamptz) ELSE r.read_at END AS read_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationInboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InboxItem> list(Long userId, Instant joinedAt, Instant now,
                                Instant cursorAt, Long cursorId, int limit) {
        String cursorClause = cursorAt == null ? "" :
                " AND (n.published_at, n.id) < (:cursorAt, :cursorId)";
        return jdbc.query("SELECT " + ITEM_COLUMNS + " FROM notifications n " +
                        "LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = :userId " +
                        "WHERE " + VISIBLE + cursorClause +
                        " ORDER BY n.published_at DESC, n.id DESC LIMIT :limit",
                parameters(userId, joinedAt, now)
                        .addValue("cursorAt", cursorAt == null ? null : Timestamp.from(cursorAt))
                        .addValue("cursorId", cursorId)
                        .addValue("limit", limit),
                (rs, row) -> item(rs));
    }

    public Optional<InboxItem> findVisible(Long userId, Instant joinedAt, Instant now, Long id) {
        return jdbc.query("SELECT " + ITEM_COLUMNS + " FROM notifications n " +
                        "LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = :userId " +
                        "WHERE n.id = :id AND " + VISIBLE,
                parameters(userId, joinedAt, now).addValue("id", id),
                (rs, row) -> item(rs)).stream().findFirst();
    }

    public boolean hasUnread(Long userId, Instant joinedAt, Instant now) {
        Boolean result = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notifications n " +
                        "WHERE " + VISIBLE +
                        " AND NOT (n.type = 'CRITICAL_NOTICE' AND n.published_at < :joinedAt) " +
                        "AND NOT EXISTS (SELECT 1 FROM notification_reads r " +
                        "WHERE r.notification_id = n.id AND r.user_id = :userId))",
                parameters(userId, joinedAt, now), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public void markRead(Long userId, Long id, Instant now) {
        jdbc.update("INSERT INTO notification_reads (user_id, notification_id, read_at) " +
                        "VALUES (:userId, :id, :now) ON CONFLICT (user_id, notification_id) DO NOTHING",
                new MapSqlParameterSource().addValue("userId", userId).addValue("id", id).addValue("now", Timestamp.from(now)));
    }

    private MapSqlParameterSource parameters(Long userId, Instant joinedAt, Instant now) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("joinedAt", Timestamp.from(joinedAt))
                .addValue("now", Timestamp.from(now))
                .addValue("cutoff", Timestamp.from(now.minusSeconds(90L * 24 * 60 * 60)));
    }

    private InboxItem item(ResultSet rs) throws SQLException {
        return new InboxItem(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                rs.getString("body"), rs.getString("link"),
                rs.getTimestamp("published_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant());
    }
}
