package com.studysprout.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class BucketRegistry {

    private final long capacity;
    private final double refillTokensPerSecond;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public BucketRegistry(long capacity, double refillTokensPerSecond){
        this(capacity, refillTokensPerSecond, System::nanoTime);
    }

    BucketRegistry(long capacity, double refillTokensPerSecond, LongSupplier nanoClock){
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.nanoClock = nanoClock;
    }

    public boolean tryConsume(String key, int tokens){
        TokenBucket bucket = buckets.computeIfAbsent(key,
            k -> new TokenBucket(capacity, refillTokensPerSecond, nanoClock));

        return bucket.tryConsume(tokens);
    }

    public boolean tryConsume(String key){
        return tryConsume(key, 1);
    }

    public long getAvailableTokens(String key){
        TokenBucket bucket = buckets.get(key);
        return bucket == null ? capacity : bucket.getAvailableTokens();
    }

    public int trackedKeyCount(){
        return buckets.size();
    }
}
