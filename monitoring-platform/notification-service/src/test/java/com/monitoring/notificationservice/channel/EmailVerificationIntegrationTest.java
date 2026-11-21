package com.monitoring.notificationservice.channel;

import com.monitoring.notificationservice.channel.UserChannelRepository.ChannelInfo;
import com.monitoring.notificationservice.delivery.MailProperties;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private UserChannelRepository channelRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private CapturingMailSender mailSender;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        mailSender = new CapturingMailSender();
        service = new EmailVerificationService(
            jdbc, channelRepository, mailSender,
            new MailProperties("no-reply@monitoring.local", "http://localhost:8083", 24, 60),
            new TransactionTemplate(transactionManager));
    }

    @Test
    void requestCreatesPendingChannelAndSendsLetterWithLink() throws Exception {
        EmailVerificationService.VerificationRequested result =
            service.requestVerification(USER_ID, "  Alice@Example.COM ");

        // Адрес нормализован, а наружу отдаётся только замаскированный
        assertThat(result.destinationHint()).isEqualTo("a***@example.com");
        assertThat(mailSender.recipients).containsExactly("alice@example.com");
        assertThat(mailSender.lastBody()).contains("http://localhost:8083/verify-email?token=");

        ChannelInfo channel = channelRepository.findOne(USER_ID, "EMAIL").orElseThrow();
        assertThat(channel.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(channel.active()).isFalse();
        // До подтверждения доставка в этот адрес невозможна
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).isEmpty();
    }

    @Test
    void tokenIsStoredOnlyAsHash() throws Exception {
        service.requestVerification(USER_ID, "alice@example.com");
        String token = tokenFromLetter();

        Integer plainTextRows = jdbc.queryForObject(
            "SELECT count(*) FROM email_verification_token WHERE token_hash = ?", Integer.class, token);
        assertThat(plainTextRows).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT length(token_hash) FROM email_verification_token", Integer.class)).isEqualTo(64);
    }

    @Test
    void confirmationActivatesChannel() throws Exception {
        service.requestVerification(USER_ID, "alice@example.com");

        String hint = service.confirm(tokenFromLetter());

        assertThat(hint).isEqualTo("a***@example.com");
        ChannelInfo channel = channelRepository.findOne(USER_ID, "EMAIL").orElseThrow();
        assertThat(channel.status()).isEqualTo("ACTIVE");
        assertThat(channel.active()).isTrue();
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).contains("alice@example.com");
    }

    @Test
    void tokenWorksOnlyOnce() throws Exception {
        service.requestVerification(USER_ID, "alice@example.com");
        String token = tokenFromLetter();

        service.confirm(token);

        assertStatus(() -> service.confirm(token), HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredTokenIsRejectedAndChannelStaysPending() throws Exception {
        service.requestVerification(USER_ID, "alice@example.com");
        jdbc.update("UPDATE email_verification_token SET expires_at = now() - interval '1 second'");

        assertStatus(() -> service.confirm(tokenFromLetter()), HttpStatus.NOT_FOUND);

        assertThat(channelRepository.findOne(USER_ID, "EMAIL").orElseThrow().status())
            .isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void unknownTokenGivesSameAnswerAsExpiredOne() {
        assertStatus(() -> service.confirm("совсем-не-токен"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.confirm(null), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.confirm("   "), HttpStatus.NOT_FOUND);
    }

    @Test
    void changingAddressStopsOldAddressImmediatelyAndInvalidatesOldLink() throws Exception {
        service.requestVerification(USER_ID, "old@example.com");
        service.confirm(tokenFromLetter());
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).contains("old@example.com");

        service.requestVerification(USER_ID, "new@example.com");

        // Новый адрес ещё не подтверждён, а на старый письма уже не идут
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).isEmpty();

        service.confirm(tokenFromLetter());
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).contains("new@example.com");
    }

    @Test
    void newRequestInvalidatesPreviousPendingLink() throws Exception {
        service.requestVerification(USER_ID, "first@example.com");
        String firstToken = tokenFromLetter();
        makeCooldownPassed();

        service.requestVerification(USER_ID, "second@example.com");
        String secondToken = tokenFromLetter();

        assertStatus(() -> service.confirm(firstToken), HttpStatus.NOT_FOUND);
        service.confirm(secondToken);
        assertThat(channelRepository.findActiveDestination(USER_ID, "EMAIL")).contains("second@example.com");
    }

    @Test
    void rapidRepeatedRequestsAreRateLimited() {
        service.requestVerification(USER_ID, "alice@example.com");

        assertStatus(() -> service.requestVerification(USER_ID, "alice@example.com"), HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mailSender.sent).hasSize(1);

        makeCooldownPassed();
        service.requestVerification(USER_ID, "alice@example.com");
        assertThat(mailSender.sent).hasSize(2);
    }

    @Test
    void failedSendRemovesTokenAndAllowsImmediateRetry() {
        mailSender.failure = new MailSendException("SMTP недоступен");

        assertStatus(() -> service.requestVerification(USER_ID, "alice@example.com"), HttpStatus.BAD_GATEWAY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verification_token", Integer.class)).isZero();

        // Пауза не должна наказывать за наш собственный сбой
        mailSender.failure = null;
        service.requestVerification(USER_ID, "alice@example.com");
        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void invalidAddressesAreRejectedWithoutSendingAnything() {
        for (String bad : new String[]{"not-an-email", "a b@c.com", "x@y", "@example.com", "a@@example.com",
            "alice@example.com\nBcc: victim@example.com", "", "   "}) {
            assertStatus(() -> service.requestVerification(USER_ID, bad), HttpStatus.BAD_REQUEST);
        }
        assertThat(mailSender.sent).isEmpty();
        assertThat(channelRepository.findOne(USER_ID, "EMAIL")).isEmpty();
    }

    @Test
    void discardingPendingRemovesTheLink() throws Exception {
        service.requestVerification(USER_ID, "alice@example.com");
        String token = tokenFromLetter();

        service.discardPending(USER_ID);

        assertStatus(() -> service.confirm(token), HttpStatus.NOT_FOUND);
    }

    // ---------- вспомогательные методы ----------

    private void makeCooldownPassed() {
        jdbc.update("UPDATE email_verification_token SET created_at = now() - interval '2 minutes'");
    }

    private String tokenFromLetter() throws Exception {
        Matcher matcher = TOKEN.matcher(mailSender.lastBody());
        assertThat(matcher.find()).as("в письме есть ссылка с токеном").isTrue();
        return matcher.group(1);
    }

    private void assertStatus(ThrowingCallable action, HttpStatus expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    /** Вместо настоящего SMTP: запоминает письма и умеет имитировать сбой отправки. */
    static class CapturingMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();
        final List<String> recipients = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void send(MimeMessage mimeMessage) {
            if (failure != null) {
                throw failure;
            }
            sent.add(mimeMessage);
            try {
                recipients.add(mimeMessage.getAllRecipients()[0].toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String lastBody() throws Exception {
            return (String) sent.get(sent.size() - 1).getContent();
        }
    }
}
