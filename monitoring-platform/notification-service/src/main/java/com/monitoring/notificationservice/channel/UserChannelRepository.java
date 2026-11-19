package com.monitoring.notificationservice.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Канал доставляет уведомления, только если он ВКЛЮЧЁН пользователем (enabled)
 * И исправен с точки зрения системы (status = ACTIVE). Эти два признака
 * намеренно раздельные: интерфейс должен отличать "я выключил" от "сломалось".
 */
@Repository
public class UserChannelRepository {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_BROKEN = "BROKEN";

    private static final String DEFAULT_BROKEN_REASON =
        "Канал отключён системой: бот заблокирован или чат недоступен";

    private final JdbcTemplate jdbcTemplate;

    public UserChannelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Публичное представление канала. destination (chat_id, адрес) наружу НЕ отдаём.
     * active = enabled && status == ACTIVE: поле оставлено для обратной совместимости
     * с клиентами, которые знают только "работает / не работает".
     */
    public record ChannelInfo(String channelType, boolean enabled, String status,
                              String statusReason, boolean active, Instant createdAt) {
    }

    public List<ChannelInfo> findByUserId(UUID userId) {
        return jdbcTemplate.query(
            """
            SELECT channel_type, enabled, status, status_reason,
                   (enabled AND status = 'ACTIVE') AS active, created_at
            FROM user_channel
            WHERE user_id = ?
            ORDER BY channel_type
            """,
            (rs, rowNum) -> new ChannelInfo(
                rs.getString("channel_type"),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getString("status_reason"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()
            ),
            userId
        );
    }

    public Optional<ChannelInfo> findOne(UUID userId, String channelType) {
        return findByUserId(userId).stream()
            .filter(channel -> channel.channelType().equals(channelType))
            .findFirst();
    }

    /**
     * Адресат канала, в который можно доставлять прямо сейчас (для Telegram — chat_id),
     * или пусто, если канал не подключён, выключен пользователем или сломан.
     */
    public Optional<String> findActiveDestination(UUID userId, String channelType) {
        List<String> destinations = jdbcTemplate.query(
            """
            SELECT destination
            FROM user_channel
            WHERE user_id = ? AND channel_type = ? AND enabled = true AND status = 'ACTIVE'
            """,
            (rs, rowNum) -> rs.getString("destination"),
            userId, channelType
        );
        return destinations.stream().findFirst();
    }

    /**
     * Привязка канала. Повторная привязка (например, другой Telegram-аккаунт)
     * заменяет адресата, снова включает канал и снимает пометку "сломан": человек,
     * который заново привязывает канал, явно хочет получать уведомления.
     * Уникальность (user_id, channel_type) обеспечивает ограничение из миграции 002,
     * поэтому upsert атомарен.
     */
    public void upsert(UUID userId, String channelType, String destination) {
        jdbcTemplate.update(
            """
            INSERT INTO user_channel (user_id, channel_type, destination, enabled, status, status_reason, verified_at)
            VALUES (?, ?, ?, true, 'ACTIVE', NULL, now())
            ON CONFLICT (user_id, channel_type)
            DO UPDATE SET destination = EXCLUDED.destination,
                          enabled = true,
                          status = 'ACTIVE',
                          status_reason = NULL,
                          verified_at = now()
            """,
            userId, channelType, destination
        );
    }

    /** Переключатель пользователя. Возвращает false, если такого канала нет. */
    public boolean setEnabled(UUID userId, String channelType, boolean enabled) {
        return jdbcTemplate.update(
            "UPDATE user_channel SET enabled = ? WHERE user_id = ? AND channel_type = ?",
            enabled, userId, channelType
        ) > 0;
    }

    /**
     * Помечает канал сломанным (доставка невозможна), не трогая выбор пользователя.
     * Название deactivate сохранено ради существующих вызовов; смысл — markBroken.
     */
    public void deactivate(UUID userId, String channelType) {
        markBroken(userId, channelType, DEFAULT_BROKEN_REASON);
    }

    public void markBroken(UUID userId, String channelType, String reason) {
        jdbcTemplate.update(
            "UPDATE user_channel SET status = 'BROKEN', status_reason = ? WHERE user_id = ? AND channel_type = ?",
            reason, userId, channelType
        );
    }

    public boolean delete(UUID userId, String channelType) {
        return jdbcTemplate.update(
            "DELETE FROM user_channel WHERE user_id = ? AND channel_type = ?",
            userId, channelType
        ) > 0;
    }
}
