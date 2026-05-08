package com.pitstop.http

import com.pitstop.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every request's base URL to the user-configured `apiBaseUrl` and attaches the
 * INGEST token as a bearer header. Mirrors the dynamic-base-URL pattern from zonik-app.
 */
@Singleton
class PitstopAuthInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val secrets = runBlocking { settingsRepository.current() }
        val baseUrl = secrets.settings.apiBaseUrl.trim()
            .ifEmpty { return chain.proceed(chain.request()) }
        val token = secrets.ingestToken
        val newBase = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())

        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .scheme(newBase.scheme)
            .host(newBase.host)
            .port(newBase.port)
            .build()

        val builder = original.newBuilder().url(newUrl)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return chain.proceed(builder.build())
    }
}
