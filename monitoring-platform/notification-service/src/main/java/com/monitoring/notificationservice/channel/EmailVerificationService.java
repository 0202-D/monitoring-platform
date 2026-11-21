package com.monitoring.notificationservice.channel;

import com.monitoring.notificationservice.delivery.MailProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Подтверждение адреса электронной почты одноразовой ссылкой.
 * Принимать любой введённый адрес без подтверждения нельзя: наш сервис можно было бы
 * использовать, чтобы слать письма постороннему человеку. Поэтому канал создаётся в
 * статусе PENDING_VERIFICATION, доставка в него не идёт, а в адрес отправляется ссылка
 * с токеном. Токен одноразовый, ограничен по времени, в базе хранится только его хеш.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final int MAX_ADDRESS_LENGTH = 254;
    // Практичная проверка формата (ASCII-адреса). Пробелы и переводы строк исключены,
    // это же защищает от подстановки заголовков письма
    private static final Pattern ADDRESS = Pattern.compile(
        "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$");

    public record VerificationRequested(String destinationHint, Instant expiresAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final UserChannelRepository channelRepository;
    private final JavaMailSender mailSender;
    private final MailProperties properties;
    private final TransactionTemplate transactionTemplate;

    public EmailVerificationService(JdbcTemplate jdbcTemplate,
                                    UserChannelRepository channelRepository,
                                    JavaMailSender mailSender,
                                    MailProperties properties,
                                    TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.channelRepository = channelRepository;
        this.mailSender = mailSender;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public VerificationRequested requestVerification(UUID userId, String rawAddress) {
        String address = normalize(rawAddress);
        enforceCooldown(userId);

        String token = Tokens.newToken();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(properties.verificationTtlHours()));

        // Запись в БД короткая и атомарная; письмо отправляем уже после неё,
        // чтобы транзакция не висела на медленном SMTP-сервере
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM email_verification_token WHERE user_id = ? OR expires_at < now()", userId);
            jdbcTemplate.update(
                "INSERT INTO email_verification_token (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                Tokens.sha256(token), userId, Timestamp.from(expiresAt));
            channelRepository.upsertPendingEmail(userId, address);
        });

        try {
            sendVerificationMail(address, token);
        } catch (MailException e) {
            // Письмо не ушло: токен бесполезен, повторить можно сразу, без ожидания паузы.
            // Адрес в лог не пишем: это персональные данные
            jdbcTemplate.update("DELETE FROM email_verification_token WHERE user_id = ?", userId);
            log.warn("Не удалось отправить письмо подтверждения пользователю {}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось отправить письмо подтверждения");
        }

        return new VerificationRequested(UserChannelRepository.maskEmail(address), expiresAt);
    }

    /**
     * Подтверждение по ссылке из письма. Токен потребляется атомарно (DELETE ... RETURNING):
     * две одновременные попытки с одним токеном не смогут обе сработать. Любая причина
     * отказа даёт один и тот же ответ: так нельзя выяснить, существует ли токен.
     */
    public String confirm(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw invalidLink();
        }
        return transactionTemplate.execute(status -> {
            List<UUID> users = jdbcTemplate.query(
                "DELETE FROM email_verification_token WHERE token_hash = ? AND expires_at > now() RETURNING user_id",
                (rs, rowNum) -> UUID.fromString(rs.getString("user_id")),
                Tokens.sha256(token));
            if (users.isEmpty()) {
                throw invalidLink();
            }
            // Если адрес успели сменить или отвязать, канал уже не ждёт подтверждения;
            // откат вернёт токен на место, но ссылка всё равно считается недействительной
            String address = channelRepository.activatePending(users.getFirst(), "EMAIL")
                .orElseThrow(EmailVerificationService::invalidLink);
            return UserChannelRepository.maskEmail(address);
        });
    }

    /** Сбрасывает ожидающее подтверждение (при отвязке канала). */
    public void discardPending(UUID userId) {
        jdbcTemplate.update("DELETE FROM email_verification_token WHERE user_id = ?", userId);
    }

    private String normalize(String rawAddress) {
        String address = rawAddress == null ? "" : rawAddress.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH || !ADDRESS.matcher(address).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный адрес электронной почты");
        }
        return address;
    }

    private void enforceCooldown(UUID userId) {
        Integer recent = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM email_verification_token "
                + "WHERE user_id = ? AND created_at > now() - (? * interval '1 second')",
            Integer.class, userId, properties.resendCooldownSeconds());
        if (recent != null && recent > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Письмо уже отправлено. Повторить можно через " + properties.resendCooldownSeconds() + " с");
        }
    }

    private void sendVerificationMail(String address, String token) {
        String link = properties.webBaseUrl().replaceAll("/+$", "") + "/verify-email?token=" + token;
        String body = "Здравствуйте!\n\n"
            + "Чтобы получать уведомления о состоянии мониторов на этот адрес, подтвердите его по ссылке:\n"
            + link + "\n\n"
            + "Ссылка действует " + properties.verificationTtlHours() + " ч. "
            + "Если вы не запрашивали подтверждение, просто проигнорируйте это письмо.";
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(address);
            helper.setSubject("Подтвердите адрес для уведомлений Monitoring Platform");
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailPreparationException("Не удалось сформировать письмо подтверждения", e);
        }
    }

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ссылка недействительна или устарела");
    }
}
