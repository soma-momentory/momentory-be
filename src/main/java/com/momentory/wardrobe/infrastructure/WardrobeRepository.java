package com.momentory.wardrobe.infrastructure;

import com.momentory.wardrobe.application.WardrobeResults.WardrobeOutfit;
import com.momentory.wardrobe.domain.ItemTier;
import com.momentory.wardrobe.domain.TangerineReason;
import com.momentory.wardrobe.domain.WardrobeSlot;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 옷장 · 귤 저장소. 잔액 차감과 보상 한 번 주기는 조건이 붙은 SQL 한 줄로 처리해 두 번 눌러도 한 번만 반영된다. */
@Repository
public class WardrobeRepository {

    public record ItemRow(String id, WardrobeSlot slot, ItemTier tier, int price, boolean onSale, boolean owned) {
    }

    private static final String ITEM_COLUMNS = """
            i.id, i.slot, i.tier, i.price, i.on_sale,
            (i.tier = 'FREE' OR o.item_id IS NOT NULL) AS owned
            FROM wardrobe_items i
            LEFT JOIN user_wardrobe_items o ON o.item_id = i.id AND o.user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WardrobeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 상점에 보일 것 — 무료 · 판매 중인 귤 아이템 · 이미 가진 것. */
    public List<ItemRow> shopItems(Long userId) {
        return jdbc.query("SELECT " + ITEM_COLUMNS + """
                        WHERE i.tier = 'FREE' OR o.item_id IS NOT NULL OR (i.tier = 'TANGERINE' AND i.on_sale)
                        ORDER BY i.id
                        """,
                new MapSqlParameterSource("userId", userId), (rs, row) -> item(rs));
    }

    public Optional<ItemRow> item(Long userId, String itemId) {
        return jdbc.query("SELECT " + ITEM_COLUMNS + " WHERE i.id = :itemId",
                new MapSqlParameterSource("userId", userId).addValue("itemId", itemId),
                (rs, row) -> item(rs)).stream().findFirst();
    }

    /** 처음 가지면 참, 이미 가졌으면 거짓. */
    public boolean own(Long userId, String itemId) {
        return jdbc.update("""
                        INSERT INTO user_wardrobe_items (user_id, item_id) VALUES (:userId, :itemId)
                        ON CONFLICT DO NOTHING
                        """,
                new MapSqlParameterSource("userId", userId).addValue("itemId", itemId)) == 1;
    }

    public int balance(Long userId) {
        return jdbc.query("SELECT balance FROM tangerine_wallets WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), (rs, row) -> rs.getInt(1))
                .stream().findFirst().orElse(0);
    }

    /** 잔액이 모자라면 아무것도 바꾸지 않고 거짓. */
    public boolean spend(Long userId, String itemId, int price) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("itemId", itemId).addValue("price", price);
        boolean spent = jdbc.update("""
                UPDATE tangerine_wallets SET balance = balance - :price, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = :userId AND balance >= :price
                """, params) == 1;
        if (spent) {
            jdbc.update("""
                    INSERT INTO tangerine_transactions (user_id, reason, amount, item_id)
                    VALUES (:userId, 'PURCHASE', -:price, :itemId)
                    """, params);
        }
        return spent;
    }

    /**
     * 그 사유 · 날짜의 보상을 처음 받으면 귤을 더하고 참, 이미 받았으면 거짓.
     * 대상 없는 ON CONFLICT 라 하루 한 번 제약과 첫 선물 평생 한 번 제약을 함께 받는다.
     */
    public boolean reward(Long userId, TangerineReason reason, LocalDate day, int amount) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("reason", reason.name()).addValue("amount", amount).addValue("day", day);
        boolean inserted = jdbc.update("""
                INSERT INTO tangerine_transactions (user_id, reason, amount, reward_date)
                VALUES (:userId, :reason, :amount, :day)
                ON CONFLICT DO NOTHING
                """, params) == 1;
        if (inserted) {
            jdbc.update("""
                    INSERT INTO tangerine_wallets (user_id, balance) VALUES (:userId, :amount)
                    ON CONFLICT (user_id) DO UPDATE
                    SET balance = tangerine_wallets.balance + :amount, updated_at = CURRENT_TIMESTAMP
                    """, params);
        }
        return inserted;
    }

    // ponytail: 보상 날짜를 전부 읽는다. 한 사람에 하루 한 줄이라 몇 년 써도 수천 줄이다
    public List<LocalDate> rewardDays(Long userId, TangerineReason reason) {
        return jdbc.query("""
                        SELECT reward_date FROM tangerine_transactions
                        WHERE user_id = :userId AND reason = :reason
                        """,
                new MapSqlParameterSource("userId", userId).addValue("reason", reason.name()),
                (rs, row) -> rs.getObject(1, LocalDate.class));
    }

    public List<TangerineReason> rewardReasonsOn(Long userId, LocalDate day) {
        return jdbc.query("""
                        SELECT reason FROM tangerine_transactions
                        WHERE user_id = :userId AND reward_date = :day ORDER BY id
                        """,
                new MapSqlParameterSource("userId", userId).addValue("day", day),
                (rs, row) -> TangerineReason.valueOf(rs.getString(1)));
    }

    /**
     * 그날 쉼터 한 곳을 끝까지 했는가(마지막에 기분을 골랐다). 중간에 나간 판은 세지 않는다 —
     * 앱은 기분 쪽지에서 귤을 알리므로, 쪽지를 보지 않은 판에 조용히 주면 사용자가 받은 줄 모른다.
     */
    public boolean visitedRest(Long userId, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM rest_records
                                       WHERE user_id = :userId AND record_date = :day AND completed)
                        """,
                new MapSqlParameterSource("userId", userId).addValue("day", day), Boolean.class));
    }

    /** 그날(04:00 경계) 바바의 편지를 처음 열었는가. */
    public boolean openedLetter(Long userId, Instant from, Instant until) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM letters
                                       WHERE user_id = :userId AND opened_at >= :from AND opened_at < :until)
                        """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("from", Timestamp.from(from)).addValue("until", Timestamp.from(until)),
                Boolean.class));
    }

    /** 이 순간 뒤로 위기 안내가 있었는가(회고 안전 기록). */
    public boolean hadSafetyEventSince(Long userId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM safety_events WHERE user_id = :userId AND occurred_at >= :since)
                        """,
                new MapSqlParameterSource("userId", userId).addValue("since", Timestamp.from(since)),
                Boolean.class));
    }

    public WardrobeOutfit outfit(Long userId) {
        return jdbc.query("""
                        SELECT hat_item_id, eyes_item_id, clothes_item_id FROM user_outfits WHERE user_id = :userId
                        """,
                new MapSqlParameterSource("userId", userId),
                (rs, row) -> new WardrobeOutfit(rs.getString(1), rs.getString(2), rs.getString(3)))
                .stream().findFirst().orElse(WardrobeOutfit.EMPTY);
    }

    public void saveOutfit(Long userId, WardrobeOutfit outfit) {
        jdbc.update("""
                INSERT INTO user_outfits (user_id, hat_item_id, eyes_item_id, clothes_item_id)
                VALUES (:userId, :hat, :eyes, :clothes)
                ON CONFLICT (user_id) DO UPDATE SET hat_item_id = :hat, eyes_item_id = :eyes,
                    clothes_item_id = :clothes, updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("userId", userId).addValue("hat", outfit.hat())
                .addValue("eyes", outfit.eyes()).addValue("clothes", outfit.clothes()));
    }

    private static ItemRow item(ResultSet rs) throws SQLException {
        return new ItemRow(rs.getString("id"), WardrobeSlot.valueOf(rs.getString("slot")),
                ItemTier.valueOf(rs.getString("tier")), rs.getInt("price"), rs.getBoolean("on_sale"),
                rs.getBoolean("owned"));
    }
}
