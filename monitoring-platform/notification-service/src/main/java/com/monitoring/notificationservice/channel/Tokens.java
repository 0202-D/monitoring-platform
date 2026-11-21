package com.monitoring.notificationservice.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Одноразовые токены: случайное значение для ссылки и его хеш для хранения в БД. */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 32 случайных байта в base64url без паддинга: 43 символа, безопасны для адреса ссылки. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** В БД хранится только хеш: утечка таблицы не даёт готовых ссылок подтверждения. */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязан поддерживаться любой JVM по спецификации
            throw new IllegalStateException(e);
        }
    }
}
