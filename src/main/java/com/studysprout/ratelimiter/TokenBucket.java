package com.studysprout.ratelimiter;

import java.util.function.LongSupplier;

public final class TokenBucket {
    
    private final long capacity;
    private final double refillTokensPerNano;
    private final LongSupplier nanoClock;

    private double availableTokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillTokensPerNano){
        this(capacity, refillTokensPerNano, System::nanoTime);
    }

    TokenBucket(long capacity, double refillTokensPerNano, LongSupplier nanoClock){
        if(capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if(refillTokensPerNano <= 0) throw new IllegalArgumentException("refillTokensPerNano must be > 0");
        this.capacity = capacity;
        this.refillTokensPerNano = refillTokensPerNano / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.availableTokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public synchronized boolean tryConsume(int tokens){
        if(tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        refill();
        if(availableTokens >= tokens){
            availableTokens -= tokens;
            return true;
        }
        return false;
    }

    public boolean tryConsume(){
        return tryConsume(1);    
    }

    public synchronized long getAvailableTokens(){
        refill();
        return (long) availableTokens;
    }

    private void refill(){
        long now = nanoClock.getAsLong();
        long elapsedNanos = now - lastRefillNanos;
        if(elapsedNanos <= 0) return;
        double refillAmount = elapsedNanos * refillTokensPerNano;
        availableTokens = Math.min(capacity, availableTokens + refillAmount);
        lastRefillNanos = now;
    }
}
