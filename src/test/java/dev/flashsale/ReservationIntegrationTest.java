package dev.flashsale;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReservationIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.data.redis.host",redis::getHost);
        r.add("spring.data.redis.port",()->redis.getMappedPort(6379));
        r.add("sale.stock",()->20); r.add("sale.sweep-ms",()->600000);
        r.add("sale.admission-per-second",()->10000);
    }
    @Autowired ReservationService service;
    @Autowired JdbcTemplate db;
    @Autowired TestRestTemplate http;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisClient;
    @BeforeEach void reset() {
        db.update("DELETE FROM redis_outbox");
        db.update("UPDATE inventory_unit SET reservation_id=NULL,sold=false");
        db.update("DELETE FROM reservation");
        redisClient.delete(List.of("flash:{sale1}:available","flash:{sale1}:holds","flash:{sale1}:deadlines"));
    }
    static <T> List<T> race(int count, IntFunction<T> task) throws Exception {
        try(var executor=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) { int n=i; futures.add(executor.submit(()-> { start.await(); return task.apply(n); })); }
            start.countDown(); var result=new ArrayList<T>();
            for(var f:futures) result.add(f.get(30,TimeUnit.SECONDS));
            return result;
        }
    }
    UUID reserve(int quantity) { UUID id=UUID.randomUUID(); service.reserve(id,"buyer",quantity); return id; }
    void expire(UUID id) { db.update("UPDATE reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    long number(String key) { return ((Number)service.inventory().get(key)).longValue(); }
    void assertInvariant() {
        assertEquals(20,number("available")+number("reserved")+number("sold"));
        assertEquals(0,db.queryForObject("""
            SELECT count(*) FROM inventory_unit u JOIN reservation r ON r.id=u.reservation_id
            WHERE (u.sold AND r.state <> 'CLAIMED') OR (NOT u.sold AND r.state <> 'PENDING')
            """,Integer.class));
    }
    @Test void concurrentBuyersNeverOversell() throws Exception {
        var results=race(40,n -> {
            try { return service.reserve(UUID.randomUUID(),"buyer"+n,1); }
            catch(SaleException e) { assertEquals(409,e.status); return null; }
        });
        assertTrue(results.stream().filter(Objects::nonNull).count()<=20);
        // SKIP LOCKED may reject a busy attempt; sequential retries fill remaining units.
        while(number("available")>0) reserve(1);
        assertEquals(20,number("reserved")); assertInvariant();
    }
    @Test void concurrentDuplicateKeyReservesExactlyOnce() throws Exception {
        UUID id=UUID.randomUUID();
        var rows=race(12,n->service.reserve(id,"buyer",2));
        assertTrue(rows.stream().allMatch(r->r.id().equals(id)));
        assertEquals(2,number("reserved"));
        assertEquals(409,assertThrows(SaleException.class,()->service.reserve(id,"other",2)).status);
        assertInvariant();
    }
    @Test void claimVersusReleaseHasOneWinner() throws Exception {
        UUID id=reserve(2), payment=UUID.randomUUID();
        race(2,n->n==0 ? service.payment(id,payment,true) : service.release(id));
        String state=service.get(id).state();
        assertTrue(Set.of("CLAIMED","REFUND_REQUIRED").contains(state));
        assertEquals(state,service.payment(id,payment,true).state());
        service.release(id); service.release(id); service.deliverOutbox(); service.deliverOutbox();
        assertInvariant();
    }
    @Test void lateSuccessRequiresRefundEvenBeforeSweeperRuns() {
        UUID id=reserve(3); expire(id);
        assertEquals("REFUND_REQUIRED",service.payment(id,UUID.randomUUID(),true).state());
        assertEquals(20,number("available")); service.deliverOutbox(); assertInvariant();
    }
    @Test void sweepAndLateCallbackNeverReclaimNewBuyersStock() {
        UUID old=reserve(5); expire(old); service.expireBatch(); service.deliverOutbox();
        UUID fresh=reserve(5);
        assertEquals("REFUND_REQUIRED",service.payment(old,UUID.randomUUID(),true).state());
        assertEquals("PENDING",service.get(fresh).state()); assertEquals(5,number("reserved")); assertInvariant();
    }
    @Test void expiryVersusPaymentReleasesExactlyOnce() throws Exception {
        UUID id=reserve(4); expire(id);
        race(2,n -> { if(n==0) service.expireBatch(); else service.payment(id,UUID.randomUUID(),true); return true; });
        assertEquals("REFUND_REQUIRED",service.get(id).state());
        service.expireBatch(); service.deliverOutbox(); service.deliverOutbox();
        assertEquals(20,number("available")); assertInvariant();
    }
    @Test void failureAndDuplicateCallbacksAreIdempotent() {
        UUID id=reserve(2), payment=UUID.randomUUID();
        assertEquals("RELEASED",service.payment(id,payment,false).state());
        assertEquals("RELEASED",service.payment(id,payment,false).state());
        assertThrows(SaleException.class,()->service.payment(id,payment,true));
        service.deliverOutbox(); service.deliverOutbox(); assertEquals(20,number("available")); assertInvariant();
    }
    @Test void partialMultiUnitReservationRollsBack() {
        for(int i=0;i<19;i++) reserve(1);
        assertThrows(SaleException.class,()->reserve(2));
        assertEquals(1,number("available")); assertEquals(19,number("reserved")); assertInvariant();
    }
    @Test void paymentIdentityCannotBeReused() {
        UUID first=reserve(1),second=reserve(1),payment=UUID.randomUUID();
        service.payment(first,payment,true);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->service.payment(second,payment,true));
        assertEquals("PENDING",service.get(second).state()); assertInvariant();
    }
    @Test void apiValidatesInputAndCallbackSecret() {
        assertEquals(400,http.postForEntity("/api/reservations",Map.of("customer","a","quantity",1),String.class).getStatusCode().value());
        var headers=new org.springframework.http.HttpHeaders(); headers.set("Idempotency-Key",UUID.randomUUID().toString());
        assertEquals(400,http.postForEntity("/api/reservations",new org.springframework.http.HttpEntity<>(Map.of("customer","","quantity",0),headers),String.class).getStatusCode().value());
        UUID id=reserve(1); headers.set("X-Callback-Secret","bad");
        assertEquals(401,http.postForEntity("/api/mock-payments/"+id,new org.springframework.http.HttpEntity<>(Map.of("paymentId",UUID.randomUUID(),"outcome","SUCCESS"),headers),String.class).getStatusCode().value());
    }
}
