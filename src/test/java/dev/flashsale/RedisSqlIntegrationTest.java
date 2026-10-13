package dev.flashsale;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="sale.mode=REDIS_SQL")
class RedisSqlIntegrationTest extends ReservationIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RedisGate gate;
    @Test void luaRetriesAndReleaseAreIdempotent() {
        UUID id=UUID.randomUUID();
        assertTrue(gate.reserve(id,2,20,30000)); assertTrue(gate.reserve(id,2,20,30000));
        assertEquals("18",redisClient.opsForValue().get("flash:{sale1}:available"));
        gate.settle(id,"RELEASE"); gate.settle(id,"RELEASE");
        assertEquals("20",redisClient.opsForValue().get("flash:{sale1}:available"));
    }
    @Test void redisLossCannotBypassSqlStock() {
        for(int i=0;i<20;i++) reserve(1);
        redisClient.delete("flash:{sale1}:available");
        assertThrows(SaleException.class,()->reserve(1)); assertEquals(20,number("reserved")); assertInvariant();
    }
    @Test void committedClaimIsSettledWithoutReturningStock() {
        UUID id=reserve(2); service.payment(id,UUID.randomUUID(),true);
        service.deliverOutbox(); service.deliverOutbox();
        assertEquals("18",redisClient.opsForValue().get("flash:{sale1}:available"));
        assertEquals(2,number("sold")); assertInvariant();
    }
    @Test void orphanHoldIsReapedByRedisTimeOnNextReserve() {
        UUID orphan=UUID.randomUUID(); gate.reserve(orphan,3,20,30000);
        redisClient.opsForZSet().add("flash:{sale1}:deadlines",orphan.toString(),0);
        assertTrue(gate.reserve(UUID.randomUUID(),1,20,30000));
        assertEquals("19",redisClient.opsForValue().get("flash:{sale1}:available"));
    }
    @Test void redisOutageFailsClosedAndRollsBackSql() {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
            .when(gate).reserve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong());
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,()->reserve(1));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM reservation",Integer.class));
        assertEquals(20,number("available"));
    }
    @Test void outboxSurvivesDeliveryFailure() {
        UUID id=reserve(2); service.release(id);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
            .when(gate).settle(id,"RELEASE");
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,service::deliverOutbox);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM redis_outbox WHERE NOT delivered",Integer.class));
        org.mockito.Mockito.doCallRealMethod().when(gate).settle(id,"RELEASE");
        service.deliverOutbox(); service.deliverOutbox();
        assertEquals(0,db.queryForObject("SELECT count(*) FROM redis_outbox WHERE NOT delivered",Integer.class));
        assertEquals("20",redisClient.opsForValue().get("flash:{sale1}:available")); assertInvariant();
    }
}
