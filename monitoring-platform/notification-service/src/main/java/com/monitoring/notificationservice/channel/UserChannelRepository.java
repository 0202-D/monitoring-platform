package com.monitoring.notificationservice.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class UserChannelRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserChannelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Публичное представление канала. destination (chat_id) наружу НЕ отдаём. */
    public record ChannelInfo(String channelType, boolean active, Instant createdAt) {
    }

    public List<ChannelInfo> findByUserId(UUID userId) {
        return jdbcTemplate.query(
            """
            SELECT channel_type, is_active, created_at
            FROM user_channel
            WHERE user_id = ?
            ORDER BY channel_type
            """,
            (rs, rowNum) -> new ChannelInfo(
                rs.getString("channel_type"),
                rs.getBoolean("is_active"),
                rs.getTimestamp("created_at").toInstant()
            ),
            userId
        );
    }

    /**
     * Привязка канала. Если у пользователя уже есть канал этого типа (например,
     * он перепривязал другой Telegram-аккаунт), заменяем адресата и заново
     * включаем канал. Уникальность (user_id, channel_type) обеспечивает
     * ограничение из миграции 002, поэтому upsert атомарен.
     */
    public void upsert(UUID userId, String channelType, String destination) {
        jdbcTemplate.update(
            """
            INSERT INTO user_channel (user_id, channel_type, destination)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, channel_type)
            DO UPDATE SET destination = EXCLUDED.destination, is_active = true
            """,
            userId, channelType, destination
        );
    }

    public boolean delete(UUID userId, String channelType) {
        return jdbcTemplate.update(
            "DELETE FROM user_channel WHERE user_id = ? AND channel_type = ?",
            userId, channelType
        ) > 0;
    }
}
