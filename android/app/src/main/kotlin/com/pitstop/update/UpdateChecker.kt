package com.pitstop.update

import com.pitstop.BuildConfig
import com.pitstop.log.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls GitHub Releases for the public Pr0zak/pitstop repo and reports whether
 * a newer version exists than [BuildConfig.VERSION_NAME].
 *
 * The repo is public so no auth is needed — `Accept: application/vnd.github+json`
 * keeps us on the stable API contract. We deliberately don't try to in-app
 * install the APK (that requires REQUEST_INSTALL_PACKAGES + a system prompt
 * flow that's brittle across OEMs); instead [UpdateInfo.releaseUrl] points
 * the user at the release page in their browser, which already works for
 * downloading + installing manually.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val httpClient: OkHttpClient,
    private val logBuffer: LogBuffer,
) {

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logBuffer.warn(
                        "update check non-2xx",
                        mapOf("code" to resp.code, "msg" to resp.message),
                    )
                    return@withContext null
                }
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(GhRelease.serializer(), body)
                val latestTag = parsed.tagName.removePrefix("v")
                val current = BuildConfig.VERSION_NAME
                val newer = isNewer(latestTag, current)
                logBuffer.info(
                    "update check",
                    mapOf("current" to current, "latest" to latestTag, "newer" to newer),
                )
                // Pick the .apk asset (CI publishes "pitstop-<ver>-debug.apk"
                // alongside its sha256 sidecar). We deliberately ignore the
                // .sha256 file and any other extras.
                val apk = parsed.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }
                UpdateInfo(
                    currentVersion = current,
                    latestVersion = latestTag,
                    isNewer = newer,
                    releaseUrl = parsed.htmlUrl,
                    apkAssetName = apk?.name,
                    apkSizeBytes = apk?.size ?: 0L,
                    notes = parsed.body.orEmpty().take(2_000),
                )
            }
        }.getOrElse { exc ->
            logBuffer.warn(
                "update check failed",
                mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
            )
            null
        }
    }

    /**
     * Compare two semver-shaped strings. Each is split on `.` and parsed
     * as ints — non-numeric suffixes (e.g. "0.1.17-rc1") are ignored. We
     * intentionally don't require strict semver so a tag like `0.2` still
     * compares cleanly against `0.1.17`.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        val len = maxOf(l.size, c.size)
        for (i in 0 until len) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }

    companion object {
        private const val LATEST_URL =
            "https://api.github.com/repos/Pr0zak/pitstop/releases/latest"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class GhRelease(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String,
        @kotlinx.serialization.SerialName("html_url") val htmlUrl: String,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url")
        val browserDownloadUrl: String,
        val size: Long = 0L,
    )
}

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val isNewer: Boolean,
    val releaseUrl: String,
    /** Asset filename (used for the on-disk Downloads/-… path). */
    val apkAssetName: String? = null,
    val apkSizeBytes: Long = 0L,
    val notes: String,
)
