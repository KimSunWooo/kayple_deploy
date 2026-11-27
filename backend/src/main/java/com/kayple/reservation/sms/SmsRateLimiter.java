package com.kayple.reservation.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmsRateLimiter {

    private final long minIntervalMs;
    private long nextAllowedAt = 0L;

    public SmsRateLimiter(@Value("${app.sms.worker.max-rate-per-second:5}") int maxPerSecond) {
        this.minIntervalMs = Math.max(200L, 1000L / Math.max(1, maxPerSecond));
    }

    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        if (now < nextAllowedAt) {
            try {
                Thread.sleep(nextAllowedAt - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = System.currentTimeMillis();
        }
        nextAllowedAt = now + minIntervalMs;
    }
}
