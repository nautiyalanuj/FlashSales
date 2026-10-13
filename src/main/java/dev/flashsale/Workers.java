package dev.flashsale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Workers {
    private static final Logger log=LoggerFactory.getLogger(Workers.class);
    private final ReservationService service;
    public Workers(ReservationService service) { this.service=service; }
    @Scheduled(fixedDelayString="${sale.sweep-ms:1000}", initialDelayString="${sale.sweep-ms:1000}")
    public void sweep() {
        try { service.expireBatch(); } catch (RuntimeException e) { log.warn("Expiry sweep failed; will retry", e); }
        try { service.deliverOutbox(); } catch (RuntimeException e) { log.warn("Redis settlement failed; durable outbox will retry", e); }
    }
}
