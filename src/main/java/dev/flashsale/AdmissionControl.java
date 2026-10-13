package dev.flashsale;

import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Per-instance ingress budget. Payments and cleanup bypass it to avoid starving settlement. */
@Component
public class AdmissionControl {
    private final Semaphore permits;
    private final int rate;
    private final LongSupplier nanoTime;
    private long second = -1;
    private int accepted;
    @org.springframework.beans.factory.annotation.Autowired
    public AdmissionControl(SaleProperties p) { this(p.admissionConcurrency(), p.admissionPerSecond(), System::nanoTime); }
    AdmissionControl(int concurrency, int rate, LongSupplier clock) {
        this.permits = new Semaphore(concurrency); this.rate = rate; this.nanoTime = clock;
    }
    public synchronized Lease enter() {
        long now = nanoTime.getAsLong() / 1_000_000_000L;
        if (now != second) { second = now; accepted = 0; }
        if (accepted >= rate || !permits.tryAcquire()) throw new SaleException(429, "Admission budget exhausted; retry with the same key");
        accepted++;
        return new Lease();
    }
    public final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public synchronized void close() { if (!closed) { closed = true; permits.release(); } }
    }
}
