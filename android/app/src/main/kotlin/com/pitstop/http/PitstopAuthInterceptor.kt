package com.pitstop.http

import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.log.loggableUrl
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every request's base URL to the user-configured `apiBaseUrl` and attaches the
 * INGEST token as a bearer header. Mirrors the dynamic-base-URL pattern from zonik-app.
 *
 * Logging policy: ERROR level only. We never log the bearer token bytes; the host portion
 * of the URL goes through [loggableUrl] which strips userinfo + query params. The
 * [LogBuffer] dependency is provided lazily because [PitstopApi] (which the LogShipper
 * uses to flush) is built on this same OkHttp client — direct injection would create a
 * cycle in the Hilt graph.
 */
@Singleton
class PitstopAuthInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logBuffer: Lazy<LogBuffer>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val secrets = runBlocking { settingsRepository.current() }
        val baseUrl = secrets.settings.apiBaseUrl.trim()
            .ifEmpty { return chain.proceed(chain.request()) }
        val newBase = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())

        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .scheme(newBase.scheme)
            .host(newBase.host)
            .port(newBase.port)
            .build()

        // Token selection follows the backend's auth policy:
        //   /api/fillups, /api/logs    → INGEST_TOKEN (write path)
        //   /api/vehicles, /analytics  → QUERY_TOKEN  (read path)
        // Method-based heuristic is good enough: GET → query token,
        // anything else → ingest token. Falls back to whichever is
        // populated when only one is set so existing single-token
        // users keep working without re-configuring.
        val isRead = original.method.equals("GET", ignoreCase = true)
        val token = if (isRead && secrets.queryToken.isNotBlank()) {
            secrets.queryToken
        } else if (!isRead && secrets.ingestToken.isNotBlank()) {
            secrets.ingestToken
        } else {
            // Single-token fallback.
            secrets.queryToken.ifBlank { secrets.ingestToken }
        }

        val builder = original.newBuilder().url(newUrl)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return try {
            chain.proceed(builder.build())
        } catch (t: Throwable) {
            // Don't log /api/logs failures — that would feedback-loop. The interceptor
            // path doesn't know what endpoint it's hitting cheaply enough; the LogShipper
            // already handles its own error reporting via lastFlushResult.
            val path = newUrl.encodedPath
            if (!path.contains("/api/logs")) {
                logBuffer.get().error(
                    "http request failed",
                    mapOf(
                        "url" to loggableUrl(newUrl.toString()),
                        "err" to (t.message ?: t::class.java.simpleName),
                    ),
                )
            }
            throw t
        }
    }
}
