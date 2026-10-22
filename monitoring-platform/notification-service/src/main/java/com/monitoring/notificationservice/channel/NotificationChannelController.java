package com.monitoring.notificationservice.channel;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notification-channels")
public class NotificationChannelController {

    private final TelegramLinkService telegramLinkService;
    private final UserChannelRepository channelRepository;

    public NotificationChannelController(TelegramLinkService telegramLinkService,
                                         UserChannelRepository channelRepository) {
        this.telegramLinkService = telegramLinkService;
        this.channelRepository = channelRepository;
    }

    @GetMapping
    public List<UserChannelRepository.ChannelInfo> listChannels(@AuthenticationPrincipal Jwt jwt) {
        return channelRepository.findByUserId(userId(jwt));
    }

    @PostMapping("/telegram/link")
    public TelegramLinkService.TelegramLink createTelegramLink(@AuthenticationPrincipal Jwt jwt) {
        return telegramLinkService.createLink(userId(jwt));
    }

    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTelegram(@AuthenticationPrincipal Jwt jwt) {
        if (!channelRepository.delete(userId(jwt), "TELEGRAM")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Telegram не подключён");
        }
    }

    /**
     * user_id во всех таблицах этого сервиса — Keycloak subject (sub claim),
     * тот же идентификатор, что checker-worker кладёт в события.
     */
    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
