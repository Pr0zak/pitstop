package com.pitstop.log

import android.util.Log
import com.pitstop.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process ring buffer of log entries waiting to be shipped to the backend's `/api/logs`
 * depot. The [LogShipper] drains this buffer on a coroutine job tied to the foreground
 * service.
 *
 * Drop policy: when [capacity] is exceeded, the OLDEST entry is dropped. We log that
 * eviction to logcat (not to the buffer itself, to avoid feedback loops).
 *
 * Threading: all mutations go through a `synchronized(buffer)` block. The buffer is small
 * (1000 entries) and contention is low so this is fine.
 *
 * Level threshold: `debug()` is gated by `SettingsRepository.verboseLogging`. We poll the
 * setting once and cache it in [verboseLogging] so the hot path does not have to suspend.
 * The cache is refreshed by a background coroutine reading the settings flow.
 */
@Singleton
class LogBuffer @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    /** Default 1000; safe for a few hours of debug-level traffic before flush. */
    val capacity: Int = DEFAULT_CAPACITY

    private val buffer: ArrayDeque<LogEntry> = ArrayDeque(capacity)

    /** Cached copy of `Settings.verboseLogging`. Updated reactively so debug() is non-suspending. */
    @Volatile private var verboseLogging: Boolean = false

    private val _bufferedCount = MutableStateFlow(0)
    /** Live size of the buffer for the UI. */
    val bufferedCount: StateFlow<Int> = _bufferedCount.asStateFlow()

    private val _droppedCount = MutableStateFlow(0L)
    /** Total entries evicted by the drop-oldest policy since process start. */
    val droppedCount: StateFlow<Long> = _droppedCount.asStateFlow()

    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Seed the cache and keep it fresh. Done via a fire-and-forget IO job so the
        // singleton constructor returns immediately.
        watcherScope.launch {
            // First read may suspend on disk IO; that's fine, debug() simply returns false until ready.
            settingsRepository.settings
                .map { it.verboseLogging }
                .collect { verboseLogging = it }
        }
    }

    fun debug(message: String, context: Map<String, Any?> = emptyMap()) {
        if (!verboseLogging) return
        push(Level.DEBUG, message, context)
    }

    fun info(message: String, context: Map<String, Any?> = emptyMap()) =
        push(Level.INFO, message, context)

    fun warn(message: String, context: Map<String, Any?> = emptyMap()) =
        push(Level.WARN, message, context)

    fun error(message: String, context: Map<String, Any?> = emptyMap()) =
        push(Level.ERROR, message, context)

    /**
     * Take up to [max] entries off the head (oldest first) for shipping. If the shipper
     * fails it must call [returnToHead] to put them back.
     */
    fun drain(max: Int): List<LogEntry> {
        synchronized(buffer) {
            val take = minOf(max, buffer.size)
            if (take == 0) return emptyList()
            val out = ArrayList<LogEntry>(take)
            repeat(take) { out += buffer.removeFirst() }
            _bufferedCount.value = buffer.size
            return out
        }
    }

    /** Put unshipped entries back at the head, preserving order. */
    fun returnToHead(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        synchronized(buffer) {
            // Push in reverse so the original order is restored.
            for (i in entries.indices.reversed()) {
                if (buffer.size >= capacity) {
                    buffer.removeLast() // unusual: we'd lose newer entries here. Prefer dropping
                    _droppedCount.value = _droppedCount.value + 1
                }
                buffer.addFirst(entries[i])
            }
            _bufferedCount.value = buffer.size
        }
    }

    fun snapshotSize(): Int = synchronized(buffer) { buffer.size }

    private fun push(level: Level, message: String, context: Map<String, Any?>) {
        val entry = LogEntry(
            clientTs = Instant.now(),
            level = level,
            message = message,
            context = context.toJsonObjectOrNull(),
        )
        synchronized(buffer) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
                _droppedCount.value = _droppedCount.value + 1
                // Avoid recursion into the buffer itself; logcat is fine for this notice.
                Log.w(TAG, "log buffer full (cap=$capacity); dropping oldest")
            }
            buffer.addLast(entry)
            _bufferedCount.value = buffer.size
        }
    }

    /**
     * Block until the in-memory cache has been seeded. Only useful in tests; production
     * code should never wait — `verboseLogging=false` is a safe default.
     */
    internal fun seedVerboseFlagBlocking() {
        runBlocking {
            verboseLogging = settingsRepository.settings.first().verboseLogging
        }
    }

    enum class Level(val wire: String) {
        DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");
    }

    /**
     * Single buffered log line. We keep [context] as a kotlinx-serialization JsonObject so
     * the shipper does not have to re-encode arbitrary maps at flush time.
     */
    data class LogEntry(
        val clientTs: Instant,
        val level: Level,
        val message: String,
        val context: JsonObject?,
    )

    companion object {
        private const val TAG = "LogBuffer"
        const val DEFAULT_CAPACITY = 1_000
    }
}

/**
 * Best-effort conversion of a free-form `Map<String, Any?>` into a JsonObject. Anything
 * that isn't a Number/String/Boolean is `toString()`ed. Nulls become `JsonNull`.
 *
 * This is intentionally simple: log context is meant for a handful of scalars
 * (vehicle_slug, mac, error code), not arbitrary nested objects.
 */
internal fun Map<String, Any?>.toJsonObjectOrNull(): JsonObject? {
    if (isEmpty()) return null
    val map = LinkedHashMap<String, JsonElement>(size)
    for ((k, v) in this) {
        map[k] = when (v) {
            null -> JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
    return JsonObject(map)
}
