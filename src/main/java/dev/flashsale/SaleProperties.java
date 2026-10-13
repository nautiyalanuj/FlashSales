package dev.flashsale;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Validated
@ConfigurationProperties("sale")
public record SaleProperties(@NotNull Mode mode, @Min(1) @Max(10000) int stock,
        @Min(1) @Max(100) int maxQuantity, @NotNull Duration reservationTtl,
        @Min(1) int admissionConcurrency, @Min(1) int admissionPerSecond,
        @Min(1) long sweepMs, @NotBlank String callbackSecret) {
    public enum Mode { SQL, REDIS_SQL }
    public SaleProperties {
        if (reservationTtl != null && (reservationTtl.isNegative() || reservationTtl.isZero()))
            throw new IllegalArgumentException("Reservation TTL must be positive");
    }
}
