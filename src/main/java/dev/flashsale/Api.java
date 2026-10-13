package dev.flashsale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@RequestMapping("/api")
public class Api {
    public record ReserveRequest(@NotBlank @Size(max=100) String customer, @Min(1) int quantity) {}
    public record PaymentRequest(@NotNull UUID paymentId, @NotNull Outcome outcome) {}
    public enum Outcome { SUCCESS, FAILURE }
    private final ReservationService service;
    private final AdmissionControl admission;
    private final SaleProperties p;
    public Api(ReservationService service, AdmissionControl admission, SaleProperties p) { this.service=service; this.admission=admission; this.p=p; }
    @PostMapping("/reservations")
    public ReservationService.Reservation reserve(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ReserveRequest request) {
        try (var lease=admission.enter()) { return service.reserve(key, request.customer(), request.quantity()); }
    }
    @GetMapping("/reservations/{id}")
    public ReservationService.Reservation get(@PathVariable UUID id) { return service.get(id); }
    @DeleteMapping("/reservations/{id}")
    public ReservationService.Reservation release(@PathVariable UUID id) { return service.release(id); }
    @GetMapping("/inventory")
    public Map<String,Object> inventory() { return service.inventory(); }
    /** Mock gateway callback: callers choose outcome to reproduce delayed/duplicate callbacks. */
    @PostMapping("/mock-payments/{id}")
    public ReservationService.Reservation payment(@PathVariable UUID id, @RequestHeader("X-Callback-Secret") String secret,
                                                  @Valid @RequestBody PaymentRequest request) {
        if (!MessageDigest.isEqual(p.callbackSecret().getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8)))
            throw new SaleException(401, "Invalid callback secret");
        return service.payment(id, request.paymentId(), request.outcome()==Outcome.SUCCESS);
    }

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(SaleException.class)
        ResponseEntity<?> sale(SaleException e) {
            var builder=ResponseEntity.status(e.status);
            if (e.status==429) builder.header("Retry-After","1");
            return builder.body(Map.of("error",e.getMessage()));
        }
        @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, org.springframework.web.bind.MissingRequestHeaderException.class})
        ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid or missing request fields")); }
        @ExceptionHandler(DuplicateKeyException.class)
        ResponseEntity<?> duplicate(Exception e) { return ResponseEntity.status(409).body(Map.of("error","Payment ID already used")); }
        @ExceptionHandler({DataAccessException.class, RedisConnectionFailureException.class,
            org.springframework.data.redis.RedisSystemException.class})
        ResponseEntity<?> unavailable(Exception e) {
            return ResponseEntity.status(503).header("Retry-After","1").body(Map.of("error","Storage unavailable or busy; retry with the same key"));
        }
    }
}
