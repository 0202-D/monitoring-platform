package com.monitoring.notificationservice.delivery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeliveryRetentionProperties.class)
public class RetentionConfig {
}
