package com.phonebridge.relay.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RateLimiter(private val maxRequestsPerMinute: Int) {

    private data class Bucket(
        var windowStartMs: Long,
        var count: Int
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    val rejectedCount = AtomicLong(0L)

    fun allow(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.computeIfAbsent(key) { Bucket(nowMs, 0) }
        synchronized(bucket) {
            if (nowMs - bucket.windowStartMs >= 60_000L) {
                bucket.windowStartMs = nowMs
                bucket.count = 0
            }
            return if (bucket.count < maxRequestsPerMinute) {
                bucket.count++
                true
            } else {
                rejectedCount.incrementAndGet()
                false
            }
        }
    }
}
