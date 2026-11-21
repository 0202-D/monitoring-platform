package com.monitoring.notificationservice.channel;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class EmailChannelController {

    public record SetEmailRequest(String address) {
    }

    public record ConfirmRequest(String token) {
    }

    public record VerificationResponse(String destinationHint, Instant expiresAt) {
    }

    public record ConfirmedResponse(String destinationHint) {
    }

    private final EmailVerificationService verificationService;

    public EmailChannelController(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Указать адрес: канал переходит в статус "ожидает подтверждения", на адрес уходит
     * письмо со ссылкой. Требует токен пользователя; user_id берётся из токена.
     */
    @PostMapping("/api/notification-channels/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VerificationResponse requestVerification(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody SetEmailRequest request) {
        EmailVerificationService.VerificationRequested requested = verificationService.requestVerification(
            UUID.fromString(jwt.getSubject()), request.address());
        return new VerificationResponse(requested.destinationHint(), requested.expiresAt());
    }

    /**
     * Подтверждение по ссылке из письма. Эндпоинт ПУБЛИЧНЫЙ (без токена пользователя):
     * человек может открыть письмо в другом браузере или на другом устройстве, а
     * учётными данными здесь служит сам одноразовый токен.
     */
    @PostMapping("/api/email-verification/confirm")
    public ConfirmedResponse confirm(@RequestBody ConfirmRequest request) {
        return new ConfirmedResponse(verificationService.confirm(request.token()));
    }
}
