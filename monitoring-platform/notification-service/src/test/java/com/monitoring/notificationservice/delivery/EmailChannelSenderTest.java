package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailChannelSenderTest {

    private static final MailProperties PROPERTIES =
        new MailProperties("no-reply@monitoring.local", "http://localhost:8083", 24, 60);

    @Mock
    private JavaMailSender mailSender;

    private EmailChannelSender sender;

    @BeforeEach
    void setUp() {
        sender = new EmailChannelSender(mailSender, new AlertMessageFormatter(), PROPERTIES);
    }

    @Test
    void buildsLetterForDownEvent() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        sender.send("alice@example.com", event(EventType.MONITOR_DOWN, "ConnectException"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();

        assertThat(message.getSubject()).isEqualTo("Монитор недоступен: example.com");
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("alice@example.com");
        assertThat(message.getFrom()).extracting(Address::toString).containsExactly("no-reply@monitoring.local");
        String body = (String) message.getContent();
        assertThat(body)
            .contains("https://example.com")
            .contains("Причина: ConnectException")
            .contains("http://localhost:8083/settings");
    }

    @Test
    void buildsLetterForUpEvent() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        sender.send("alice@example.com", event(EventType.MONITOR_UP, null));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Монитор снова доступен: example.com");
    }

    @Test
    void addressRejectedByMailServerIsPermanentFailure() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        SendFailedException rejected = new SendFailedException("550 No such user", null,
            new Address[0], new Address[0], new Address[]{new InternetAddress("nobody@example.com")});
        doThrow(new MailSendException("Mail server rejected the address", rejected))
            .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send("nobody@example.com", event(EventType.MONITOR_DOWN, "boom")))
            .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
    }

    @Test
    void unreachableMailServerIsTransientFailure() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("Mail server connection failed",
            new MessagingException("Could not connect", new ConnectException("refused"))))
            .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send("alice@example.com", event(EventType.MONITOR_DOWN, "boom")))
            .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isFalse());
    }

    @Test
    void ourOwnAuthenticationFailureDoesNotBreakTheUsersChannel() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailAuthenticationException("535 authentication failed"))
            .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send("alice@example.com", event(EventType.MONITOR_DOWN, "boom")))
            .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isFalse());
    }

    @Test
    void malformedRecipientInDatabaseIsPermanentFailure() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        assertThatThrownBy(() -> sender.send("not an address", event(EventType.MONITOR_DOWN, "boom")))
            .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
    }

    private MonitorStatusEvent event(EventType type, String details) {
        return MonitorStatusEvent.newBuilder()
            .setEventId("event-1")
            .setMonitorId("monitor-1")
            .setUserId("e754da5a-9fb3-47e2-b1f3-68d6163ecc45")
            .setEventType(type)
            .setTargetUrl("https://example.com")
            .setOccurredAt(1_700_000_000_000L)
            .setDetails(details)
            .build();
    }
}
