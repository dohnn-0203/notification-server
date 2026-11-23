package dev.riri.notification;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "notification")
public record NotificationSettings(
        @NotBlank String keyPrefix,
        boolean workerEnabled,
        @Min(1) long pollDelayMs,
        @Min(1) long retryDelayMs,
        @Min(1) int maxAttempts) {
}
