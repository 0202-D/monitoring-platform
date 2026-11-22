package com.monitoring.webui.client.dto;

import java.time.Instant;

public record EmailVerificationDto(String destinationHint, Instant expiresAt) {
}
