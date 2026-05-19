package com.pitstop.http

import com.pitstop.log.LogBuffer
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-level interceptor for the OkHttp cache (CACHE-1).
 *
 * Two responsibilities. **Application-layer** (this class) handles
 * offline fallback: when the network round-trip throws — server down,
 * Wi-Fi off, DNS fails, deep Doze, anything — it re-issues the request
 * with `CacheControl.FORCE_CACHE` so any stored response within
 * [MAX_STALE_HOURS] of its capture time is served instead of crashing
 * the screen. Writes (POST/DELETE/PATCH/PUT) are passed through
 * untouched; only GETs benefit.
 *
 * Pair with [CacheRewriteInterceptor] which runs at the network layer
 * and rewrites response headers so the cache actually stores them
 * (the backend doesn't emit Cache-Control of its own).
 */
@Singleton
class OfflineCacheInterceptor @Inject constructor(
    private val logBuffer: LogBuffer,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method.uppercase() !in CACHEABLE_METHODS) {
            return chain.proceed(original)
        }
        return try {
            chain.proceed(original)
        } catch (t: IOException) {
            // Network couldn't be reached at all (DNS / connect / read
            // timeout). Re-issue with FORCE_CACHE — OkHttp will hand back
            // the most recent cached response within max-stale, or 504
            // Gateway Timeout if there's nothing on disk.
            logBuffer.info(
                "http offline fallback (CACHE-1)",
                mapOf(
                    "url" to original.url.encodedPath,
                    "err" to (t.message ?: t::class.java.simpleName),
                ),
            )
            val cached = original.newBuilder()
                .cacheControl(
                    CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(MAX_STALE_HOURS.toInt(), TimeUnit.HOURS)
                        .build(),
                )
                .build()
            chain.proceed(cached)
        }
    }

    private companion object {
        val CACHEABLE_METHODS = setOf("GET", "HEAD")
        const val MAX_STALE_HOURS = 24L * 7L  // a week — cars don't change fast
    }
}

/**
 * Network-layer interceptor — rewrites response headers so the cache
 * actually stores GETs. Pitstop's FastAPI backend doesn't emit
 * Cache-Control of its own; without this, OkHttp's Cache silently
 * ignores everything.
 *
 * Strategy: every 2xx GET response gets `Cache-Control: public,
 * max-age=60, stale-if-error=604800`. Fresh-window is short (so a
 * connected client always sees up-to-date data on a normal fetch) but
 * stale-if-error allows the disk copy to back up [OfflineCacheInterceptor]
 * for a week of offline use.
 */
@Singleton
class CacheRewriteInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method.uppercase() != "GET") return response
        if (!response.isSuccessful) return response
        // Drop existing no-store/private headers so OkHttp will cache.
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            .header(
                "Cache-Control",
                "public, max-age=$FRESH_SECONDS, stale-if-error=$STALE_SECONDS",
            )
            .build()
    }

    private companion object {
        const val FRESH_SECONDS = 60L
        const val STALE_SECONDS = 7L * 24L * 60L * 60L  // 1 week
    }
}
