package dev.flashsale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public class Startup implements ApplicationRunner {
    private final JdbcTemplate db;
    private final SaleProperties p;
    private final TransactionTemplate tx;
    public Startup(JdbcTemplate db, SaleProperties p, PlatformTransactionManager tm) { this.db=db; this.p=p; tx=new TransactionTemplate(tm); }
    @Override public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            // Serializes first-time seeding across application replicas.
            db.execute("SELECT pg_advisory_xact_lock(748921)");
            db.update("INSERT INTO sale(id,mode,initial_stock) VALUES (1,?,?) ON CONFLICT DO NOTHING", p.mode().name(), p.stock());
            var sale = db.queryForMap("SELECT mode,initial_stock FROM sale WHERE id=1");
            if (!sale.get("mode").equals(p.mode().name()) || ((Number)sale.get("initial_stock")).intValue()!=p.stock())
                throw new IllegalStateException("Existing sale differs from configured mode/stock. Use a fresh database for another scenario.");
            db.update("INSERT INTO inventory_unit(sale_id,unit_id) SELECT 1,n FROM generate_series(1,?) n ON CONFLICT DO NOTHING", p.stock());
        });
    }
}
