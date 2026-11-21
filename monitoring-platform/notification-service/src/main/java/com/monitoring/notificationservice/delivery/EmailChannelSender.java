package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Отправка уведомления письмом. Различает два вида сбоев:
 *  - адрес отклонён почтовым сервером (несуществующий ящик): повторять бессмысленно,
 *    канал помечается сломанным, пользователь увидит причину и сможет указать другой адрес;
 *  - всё остальное (SMTP недоступен, ошибка входа, таймаут): временный сбой, будет повтор.
 *    Ошибка входа на наш SMTP-сервер — проблема нашей настройки, а не адреса пользователя,
 *    поэтому каналы пользователей из-за неё не ломаются.
 */
@Component
public class EmailChannelSender implements ChannelSender {

    private final JavaMailSender mailSender;
    private final AlertMessageFormatter formatter;
    private final MailProperties properties;

    public EmailChannelSender(JavaMailSender mailSender, AlertMessageFormatter formatter,
                              MailProperties properties) {
        this.mailSender = mailSender;
        this.formatter = formatter;
        this.properties = properties;
    }

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public boolean isConfigured() {
        // По умолчанию письма уходят в локальный Mailpit; отдельной настройки "включить" нет
        return true;
    }

    @Override
    public void send(String destination, MonitorStatusEvent event) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(destination);
            helper.setSubject(subject(event));
            helper.setText(body(event), false);
            mailSender.send(message);
        } catch (AddressException e) {
            // Испорченный адрес в базе не исправится повтором
            throw new DeliveryException("Некорректный адрес получателя", true);
        } catch (MessagingException e) {
            throw new DeliveryException("Не удалось сформировать письмо: " + e.getMessage(), false);
        } catch (MailException e) {
            throw new DeliveryException(e.getMessage(), isPermanent(e));
        }
    }

    private String subject(MonitorStatusEvent event) {
        String host = hostOf(event.getTargetUrl());
        return switch (event.getEventType()) {
            case MONITOR_DOWN -> "Монитор недоступен: " + host;
            case MONITOR_UP -> "Монитор снова доступен: " + host;
            default -> "Событие монитора: " + host;
        };
    }

    private String body(MonitorStatusEvent event) {
        return formatter.format(event)
            + "\n\n--\n"
            + "Вы получаете это письмо, потому что включили email-уведомления в Monitoring Platform.\n"
            + "Управлять уведомлениями: " + properties.webBaseUrl().replaceAll("/+$", "") + "/settings";
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** Адрес отклонён почтовым сервером: он явно указан в SendFailedException как недействительный. */
    static boolean isPermanent(MailException e) {
        if (e instanceof MailSendException sendException) {
            for (Exception failure : sendException.getMessageExceptions()) {
                if (rejectsAddress(failure)) {
                    return true;
                }
            }
        }
        return rejectsAddress(e);
    }

    private static boolean rejectsAddress(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException failed) {
                Address[] invalid = failed.getInvalidAddresses();
                if (invalid != null && invalid.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
