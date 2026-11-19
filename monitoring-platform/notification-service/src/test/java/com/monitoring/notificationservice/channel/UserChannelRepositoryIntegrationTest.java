package com.monitoring.notificationservice.channel;

import com.monitoring.notificationservice.channel.UserChannelRepository.ChannelInfo;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserChannelRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");

    @Autowired
    private UserChannelRepository repository;

    @Test
    void linkingCreatesEnabledActiveVerifiedChannel() {
        repository.upsert(USER_ID, "TELEGRAM", "555");

        ChannelInfo channel = repository.findOne(USER_ID, "TELEGRAM").orElseThrow();
        assertThat(channel.enabled()).isTrue();
        assertThat(channel.status()).isEqualTo("ACTIVE");
        assertThat(channel.active()).isTrue();
        assertThat(channel.statusReason()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT verified_at IS NOT NULL FROM user_channel", Boolean.class)).isTrue();
    }

    @Test
    void deliveryRequiresChannelBothEnabledAndActive() {
        repository.upsert(USER_ID, "TELEGRAM", "555");
        assertThat(repository.findActiveDestination(USER_ID, "TELEGRAM")).contains("555");

        repository.setEnabled(USER_ID, "TELEGRAM", false);
        assertThat(repository.findActiveDestination(USER_ID, "TELEGRAM")).isEmpty();

        repository.setEnabled(USER_ID, "TELEGRAM", true);
        repository.markBroken(USER_ID, "TELEGRAM", "бот заблокирован");
        assertThat(repository.findActiveDestination(USER_ID, "TELEGRAM")).isEmpty();
    }

    @Test
    void userSwitchDoesNotRepairBrokenChannel() {
        repository.upsert(USER_ID, "TELEGRAM", "555");
        repository.markBroken(USER_ID, "TELEGRAM", "бот заблокирован");

        repository.setEnabled(USER_ID, "TELEGRAM", true);

        ChannelInfo channel = repository.findOne(USER_ID, "TELEGRAM").orElseThrow();
        assertThat(channel.status()).isEqualTo("BROKEN");
        assertThat(channel.statusReason()).isEqualTo("бот заблокирован");
        assertThat(channel.active()).isFalse();
    }

    @Test
    void relinkingReenablesChannelAndClearsBrokenState() {
        repository.upsert(USER_ID, "TELEGRAM", "555");
        repository.setEnabled(USER_ID, "TELEGRAM", false);
        repository.markBroken(USER_ID, "TELEGRAM", "бот заблокирован");

        // Человек, заново привязывающий канал, явно хочет получать уведомления
        repository.upsert(USER_ID, "TELEGRAM", "777");

        ChannelInfo channel = repository.findOne(USER_ID, "TELEGRAM").orElseThrow();
        assertThat(channel.enabled()).isTrue();
        assertThat(channel.status()).isEqualTo("ACTIVE");
        assertThat(channel.statusReason()).isNull();
        assertThat(repository.findActiveDestination(USER_ID, "TELEGRAM")).contains("777");
    }

    @Test
    void deactivateMarksChannelBrokenWithDefaultReason() {
        repository.upsert(USER_ID, "TELEGRAM", "555");

        repository.deactivate(USER_ID, "TELEGRAM");

        ChannelInfo channel = repository.findOne(USER_ID, "TELEGRAM").orElseThrow();
        assertThat(channel.status()).isEqualTo("BROKEN");
        assertThat(channel.statusReason()).isNotBlank();
        // выбор пользователя остаётся прежним
        assertThat(channel.enabled()).isTrue();
    }

    @Test
    void settingEnabledOnMissingChannelReportsNothingChanged() {
        assertThat(repository.setEnabled(USER_ID, "TELEGRAM", false)).isFalse();
        assertThat(repository.findOne(USER_ID, "TELEGRAM")).isEqualTo(Optional.empty());
    }

    @Test
    void usersCannotSeeEachOthersChannels() {
        UUID other = UUID.randomUUID();
        repository.upsert(USER_ID, "TELEGRAM", "555");

        assertThat(repository.findByUserId(other)).isEmpty();
        assertThat(repository.findActiveDestination(other, "TELEGRAM")).isEmpty();
    }

    @Test
    void databaseRejectsUnknownStatus() {
        repository.upsert(USER_ID, "TELEGRAM", "555");

        assertThatThrownBy(() -> jdbc.update("UPDATE user_channel SET status = 'WEIRD'"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteRemovesChannel() {
        repository.upsert(USER_ID, "TELEGRAM", "555");

        assertThat(repository.delete(USER_ID, "TELEGRAM")).isTrue();
        assertThat(repository.delete(USER_ID, "TELEGRAM")).isFalse();
    }
}
