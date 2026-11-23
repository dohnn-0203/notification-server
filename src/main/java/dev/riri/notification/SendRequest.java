package dev.riri.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendRequest(
        @NotBlank @Size(max = 80) String recipient,
        @NotBlank @Size(max = 500) String message,
        @Min(0) @Max(10) int demoFailures,
        @Min(0) @Max(2000) int demoDelayMs) {
}
