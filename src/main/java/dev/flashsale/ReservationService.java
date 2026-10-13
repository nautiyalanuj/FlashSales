package dev.flashsale;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Service
public class ReservationService {
    public record Reservation(UUID id, String customer, int quantity, String state,
                              OffsetDateTime expiresAt, UUID paymentId, String paymentOutcome) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SaleProperties p;
    private final RedisGate redis;
    public ReservationService(JdbcTemplate db, PlatformTransactionManager manager, SaleProperties p, RedisGate redis) {
        this.db = db; this.p = p; this.redis = redis;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    public Reservation reserve(UUID id, String customer, int quantity) {
        if (quantity < 1 || quantity > p.maxQuantity()) throw new SaleException(400, "Invalid quantity; maximum is " + p.maxQuantity());
        return tx.execute(status -> {
                int inserted = db.update("""
                    INSERT INTO reservation(id,customer,quantity,state,expires_at)
                    VALUES (?,?,?,'PENDING',clock_timestamp() + (? * interval '1 millisecond'))
                    ON CONFLICT (id) DO NOTHING
                    """, id, customer, quantity, p.reservationTtl().toMillis());
                Reservation r = locked(id);
                if (!r.customer().equals(customer) || r.quantity() != quantity)
                    throw new SaleException(409, "Idempotency key reused with different request");
                if (inserted == 0) return r;
                if (p.mode() == SaleProperties.Mode.REDIS_SQL) {
                    if (!redis.reserve(id, quantity, initialStock(), p.reservationTtl().toMillis()))
                        throw new SaleException(409, "Provisional Redis stock unavailable");
                }
                List<Integer> units = db.queryForList("""
                    SELECT unit_id FROM inventory_unit
                    WHERE sale_id=1 AND reservation_id IS NULL AND NOT sold
                    ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, Integer.class, quantity);
                if (units.size() != quantity) throw new SaleException(409, "Stock unavailable or busy; retry with the same key");
                for (int unit : units) db.update("UPDATE inventory_unit SET reservation_id=? WHERE sale_id=1 AND unit_id=?", id, unit);
                return r;
        });
        // Never release a Redis hold on an ambiguous SQL COMMIT failure: SQL may have persisted it.
        // Provisional orphan holds recover through Redis logical TTL, causing temporary underselling only.
    }
    public Reservation get(UUID id) { return find(id, false); }
    private Reservation locked(UUID id) { return find(id, true); }
    private Reservation find(UUID id, boolean lock) {
        var rows = db.query("SELECT * FROM reservation WHERE id=?" + (lock ? " FOR UPDATE" : ""), (rs,n) ->
            new Reservation(rs.getObject("id", UUID.class), rs.getString("customer"), rs.getInt("quantity"),
                rs.getString("state"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("payment_id", UUID.class), rs.getString("payment_outcome")), id);
        if (rows.isEmpty()) throw new SaleException(404, "Reservation not found");
        return rows.getFirst();
    }
    public Reservation release(UUID id) {
        return tx.execute(s -> {
            Reservation r = locked(id);
            if (r.state().equals("PENDING")) finish(r, "RELEASED", false);
            return get(id);
        });
    }
    public Reservation payment(UUID id, UUID paymentId, boolean success) {
        return tx.execute(s -> {
            Reservation r = locked(id);
            String outcome = success ? "SUCCESS" : "FAILURE";
            if (r.paymentId() != null) {
                if (!r.paymentId().equals(paymentId) || !r.paymentOutcome().equals(outcome))
                    throw new SaleException(409, "Payment callback conflicts with recorded outcome");
                return r;
            }
            // Unique payment_id also prevents reusing one payment for another reservation.
            db.update("UPDATE reservation SET payment_id=?,payment_outcome=? WHERE id=?", paymentId, outcome, id);
            boolean valid = r.state().equals("PENDING") && !expired(r);
            if (success && valid) finish(r, "CLAIMED", true);
            else if (r.state().equals("PENDING")) finish(r, success ? "REFUND_REQUIRED" : "RELEASED", false);
            else if (success && !r.state().equals("CLAIMED"))
                db.update("UPDATE reservation SET state='REFUND_REQUIRED' WHERE id=?", id);
            return get(id);
        });
    }
    private boolean expired(Reservation r) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT clock_timestamp() >= ?", Boolean.class, r.expiresAt()));
    }
    private void finish(Reservation r, String state, boolean sold) {
        if (sold) db.update("UPDATE inventory_unit SET sold=true WHERE reservation_id=?", r.id());
        else db.update("UPDATE inventory_unit SET reservation_id=NULL WHERE reservation_id=? AND NOT sold", r.id());
        db.update("UPDATE reservation SET state=? WHERE id=?", state, r.id());
        if (p.mode() == SaleProperties.Mode.REDIS_SQL)
            db.update("INSERT INTO redis_outbox(reservation_id,action) VALUES (?,?) ON CONFLICT DO NOTHING", r.id(), sold ? "CLAIM" : "RELEASE");
    }
    public void expireBatch() {
        tx.executeWithoutResult(s -> {
            var ids = db.queryForList("""
                SELECT id FROM reservation WHERE state='PENDING' AND expires_at <= clock_timestamp()
                ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """, UUID.class);
            for (UUID id : ids) finish(locked(id), "EXPIRED", false);
        });
    }
    public void deliverOutbox() {
        if (p.mode() != SaleProperties.Mode.REDIS_SQL) return;
        tx.executeWithoutResult(s -> {
            var rows = db.queryForList("SELECT reservation_id,action FROM redis_outbox WHERE NOT delivered LIMIT 100 FOR UPDATE SKIP LOCKED");
            for (var row : rows) {
                UUID id = (UUID) row.get("reservation_id");
                redis.settle(id, (String) row.get("action"));
                db.update("UPDATE redis_outbox SET delivered=true WHERE reservation_id=?", id);
            }
        });
    }
    int initialStock() { return db.queryForObject("SELECT initial_stock FROM sale WHERE id=1", Integer.class); }
    public Map<String,Object> inventory() {
        return db.queryForMap("""
            SELECT count(*) AS total, count(*) FILTER (WHERE sold) AS sold,
              count(*) FILTER (WHERE reservation_id IS NOT NULL AND NOT sold) AS reserved,
              count(*) FILTER (WHERE reservation_id IS NULL) AS available FROM inventory_unit WHERE sale_id=1
            """);
    }
}
