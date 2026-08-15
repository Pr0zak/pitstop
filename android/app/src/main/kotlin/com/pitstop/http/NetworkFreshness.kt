package com.pitstop.http

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts how many times [OfflineCacheInterceptor] has fallen back to
 * the disk cache because the network round-trip failed.
 *
 * Screens snapshot the counter before a fetch and compare after: if it
 * moved, what they are showing came off disk and may be a week old.
 * Without this a refresh that quietly served stale data was reported
 * as "Updated <now>", which is worse than no timestamp at all.
 */
@Singleton
class NetworkFreshness @Inject constructor() {

    private val fallbacks = AtomicInteger(0)

    /** Monotonic count of offline fallbacks since process start. */
    fun snapshot(): Int = fallbacks.get()

    fun recordOfflineFallback() {
        fallbacks.incrementAndGet()
    }
}
