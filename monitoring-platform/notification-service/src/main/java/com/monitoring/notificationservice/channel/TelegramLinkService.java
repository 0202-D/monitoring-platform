package com.monitoring.notificationservice.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Привязка Telegram через deep-link. Пользователь НЕ вводит chat_id сам:
 * принимать chat_id от клиента значило бы позволить привязать к своему
 * аккаунту чужой чат и получать (или направлять) уведомления за другого.
 * Вместо этого сервис выдаёт одноразовую ссылку; когда пользователь
 * открывает её в Telegram и нажимает Start, бот получает токен вместе
 * с настоящим chat_id отправителя — подделать его нельзя.
 */
@Service
public class TelegramLinkService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${telegram.bot-username}")
    private String botUsername;

    public TelegramLinkService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TelegramLink(String url, Instant expiresAt) {
    }

    public TelegramLink createLink(UUID userId) {
        // 32 случайных байта -> 43 символа base64url без паддинга. Алфавит
        // base64url (A-Z a-z 0-9 _ -) совпадает с тем, что Telegram разрешает
        // в параметре /start (до 64 символов).
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant expiresAt = Instant.now().plus(TOKEN_TTL);

        // Одновременно может быть активна только одна ссылка на пользователя,
        // заодно между делом чистим всё просроченное (отдельный cleanup-джоб
        // для такой маленькой таблицы был бы лишним).
        jdbcTemplate.update(
            "DELETE FROM telegram_link_token WHERE user_id = ? OR expires_at < now()", userId);
        jdbcTemplate.update(
            "INSERT INTO telegram_link_token (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
            sha256(token), userId, Timestamp.from(expiresAt));

        return new TelegramLink("https://t.me/" + botUsername + "?start=" + token, expiresAt);
    }

    /**
     * Использует токен ровно один раз: DELETE ... RETURNING — одна атомарная
     * операция, поэтому два одновременных /start с одним токеном не смогут
     * оба его "потратить". Просроченный токен не находится вовсе.
     * Вызывается ботом (следующий этап).
     */
    public Optional<UUID> consumeToken(String token) {
        List<UUID> userIds = jdbcTemplate.query(
            """
            DELETE FROM telegram_link_token
            WHERE token_hash = ? AND expires_at > now()
            RETURNING user_id
            """,
            (rs, rowNum) -> UUID.fromString(rs.getString("user_id")),
            sha256(token)
        );
        return userIds.stream().findFirst();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязан поддерживаться любой JVM по спецификации
            throw new IllegalStateException(e);
        }
    }
}
