package dev.flashsale;

import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Atomic provisional holds. SQL remains the oversell protection backstop. */
@Component
public class RedisGate {
    private final StringRedisTemplate redis;
    // Shared hash tag keeps every Lua key in the same Redis Cluster slot.
    private static final List<String> KEYS = List.of("flash:{sale1}:available", "flash:{sale1}:holds", "flash:{sale1}:deadlines");
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        local t = redis.call('TIME')
        local now = t[1]*1000 + math.floor(t[2]/1000)
        redis.call('SET', KEYS[1], ARGV[3], 'NX')
        local expired = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', now, 'LIMIT', 0, 200)
        for _,id in ipairs(expired) do
          local q = redis.call('HGET', KEYS[2], id)
          if q then redis.call('INCRBY', KEYS[1], q); redis.call('HDEL', KEYS[2], id) end
          redis.call('ZREM', KEYS[3], id)
        end
        local existing = redis.call('HGET', KEYS[2], ARGV[1])
        if existing then
          if tonumber(existing) == tonumber(ARGV[2]) then return 1 else return 0 end
        end
        if tonumber(redis.call('GET', KEYS[1])) < tonumber(ARGV[2]) then return 0 end
        redis.call('DECRBY', KEYS[1], ARGV[2])
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
        redis.call('ZADD', KEYS[3], now+tonumber(ARGV[4]), ARGV[1])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
        local q = redis.call('HGET', KEYS[2], ARGV[1])
        if not q then return 0 end
        if ARGV[2] == 'RELEASE' then redis.call('INCRBY', KEYS[1], q) end
        redis.call('HDEL', KEYS[2], ARGV[1])
        redis.call('ZREM', KEYS[3], ARGV[1])
        return 1
        """, Long.class);
    public RedisGate(StringRedisTemplate redis) { this.redis = redis; }
    public boolean reserve(UUID id, int quantity, int initialStock, long ttlMillis) {
        return Long.valueOf(1).equals(redis.execute(RESERVE, KEYS, id.toString(), Integer.toString(quantity),
                Integer.toString(initialStock), Long.toString(ttlMillis)));
    }
    public void settle(UUID id, String action) { redis.execute(SETTLE, KEYS, id.toString(), action); }
}
