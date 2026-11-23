package dev.riri.notification;

import java.time.Instant;
import java.util.Map;

public record Notification(
        String id,
        String recipient,
        String message,
        NotificationStatus status,
        int attempts,
        int demoFailures,
        int demoDelayMs,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        String lastError) {

    static Notification from(Map<Object, Object> values) {
        return new Notification(
                values.get("id").toString(),
                values.get("recipient").toString(),
                values.get("message").toString(),
                NotificationStatus.valueOf(values.get("status").toString()),
                Integer.parseInt(values.get("attempts").toString()),
                Integer.parseInt(values.get("demoFailures").toString()),
                Integer.parseInt(values.get("demoDelayMs").toString()),
                Instant.ofEpochMilli(Long.parseLong(values.get("createdAt").toString())),
                Instant.ofEpochMilli(Long.parseLong(values.get("updatedAt").toString())),
                Instant.ofEpochMilli(Long.parseLong(values.get("nextRetryAt").toString())),
                values.get("lastError").toString());
    }
}
