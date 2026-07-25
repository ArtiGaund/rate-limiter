package com.studysprout.ratelimiter;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public final class RedisTokenBucket implements AutoCloseable {
    
    private static final String SCRIPT = """
        local capacity = tonumber(ARGV[1])
        local refill_rate = tonumber(ARGV[2])
        local requested = tonumber(ARGV[3])
        local now = tonumber(ARGV[4])

        local bucket = redis.call("HMGET", KEYS[1], "tokens", "last_refill")
        local tokens = tonumber(bucket[1])
        local last_refill = tonumber(bucket[2])

        if tokens == nil then
            tokens = capacity
            last_refill = now
        end

        local elapsed_seconds = math.max(0, (now - last_refill) / 1000.0)
        tokens = math.min(capacity, tokens + elapsed_seconds * refill_rate)

        local allowed = 0
        if tokens >= requested then
            tokens = tokens - requested
            allowed = 1
        end

        redis.call("HMSET", KEYS[1], "tokens", tokens, "last_refill", now)
        redis.call("EXPIRE", KEYS[1], 3600)

        return {allowed, tokens}
    """;

    private final JedisPool pool;
    private final long capacity;
    private final double refillTokensPerSecond;
    private final String scriptSha;

    public RedisTokenBucket(String redisUrl, long capacity, double refillTokensPerSecond){
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        this.pool = new JedisPool(poolConfig, URI.create(redisUrl));

        try(Jedis jedis = pool.getResource()){
            this.scriptSha = jedis.scriptLoad(SCRIPT);
        }
    }

    public record Result(boolean allowed, double remainingTokens) {}

    public Result tryConsume(String key, int tokens){
        try(Jedis jedis = pool.getResource()){
            List<String> keys = List.of("ratelimit:" + key);
            List<String> args = Arrays.asList(
                String.valueOf(capacity),
                String.valueOf(refillTokensPerSecond),
                String.valueOf(tokens),
                String.valueOf(System.currentTimeMillis())
            );
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) jedis.evalsha(scriptSha, keys, args);
            boolean allowed = ((Long) result.get(0)) == 1L;
            double remaining = Double.parseDouble(result.get(1).toString());
            return new Result(allowed, remaining);
        }catch(redis.clients.jedis.exceptions.JedisNoScriptException e){
            try(Jedis jedis = pool.getResource()) {
                jedis.scriptLoad(SCRIPT);
            } 
            return tryConsume(key, tokens);
        }
    }

    public boolean tryConsume(String key){
        return tryConsume(key, 1).allowed();
    }
    
    @Override
    public void close() {
        pool.close();
    }
}




