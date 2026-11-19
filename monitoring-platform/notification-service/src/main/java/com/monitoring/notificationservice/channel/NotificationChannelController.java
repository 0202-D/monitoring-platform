package com.monitoring.notificationservice.channel;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/notification-channels")
public class NotificationChannelController {

    // Поддерживаемые типы каналов; EMAIL добавится вместе с самим каналом
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("TELEGRAM");

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

    /**
     * Переключатель пользователя "получать / не получать уведомления в этот канал".
     * Меняет только выбор пользователя: состояние системы (сломан канал или нет)
     * этим запросом не затрагивается. Пользователь управляет только СВОИМИ
     * каналами: user_id берётся из токена, а не из запроса.
     */
    @PutMapping("/{channelType}")
    public UserChannelRepository.ChannelInfo setEnabled(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable("channelType") String channelType,
                                                        @RequestBody UpdateChannelRequest request) {
        String type = normalize(channelType);
        UUID userId = userId(jwt);

        if (!channelRepository.setEnabled(userId, type, request.enabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Канал не подключён");
        }
        return channelRepository.findOne(userId, type)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Канал не подключён"));
    }

    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTelegram(@AuthenticationPrincipal Jwt jwt) {
        if (!channelRepository.delete(userId(jwt), "TELEGRAM")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Telegram не подключён");
        }
    }

    private String normalize(String channelType) {
        String type = channelType.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CHANNELS.contains(type)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный канал: " + channelType);
        }
        return type;
    }

    /**
     * user_id во всех таблицах этого сервиса — Keycloak subject (sub claim),
     * тот же идентификатор, что checker-worker кладёт в события.
     */
    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
