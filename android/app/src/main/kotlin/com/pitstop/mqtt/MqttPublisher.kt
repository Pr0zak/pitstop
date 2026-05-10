package com.pitstop.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.lifecycle.Mqtt3ClientDisconnectedContext
import com.pitstop.log.LogBuffer
import com.pitstop.log.loggableUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around the HiveMQ MQTT v3 async client. We use v3 because Mosquitto's
 * default config speaks v3.1.1 cleanly without requiring v5 properties.
 *
 * Connection model: one client per process. We enable HiveMQ's automatic reconnect so
 * mid-session drops (phone sleeps, Wi-Fi flips, broker restart) are handled transparently
 * and `isConnected()` stays accurate. Without it, QoS-0 publishes succeed locally into
 * a dead socket and `isConnected()` returns stale `true` — the bridge's UI then says
 * "broker on" while nothing reaches the server. A disconnected-listener mirrors the
 * client's connection state to the log buffer so we can see drops in /debug.
 */
@Singleton
class MqttPublisher @Inject constructor(
    private val logBuffer: LogBuffer,
) {

    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var lastUrl: String? = null
    private val publishCount = AtomicLong(0)
    private val lastPublishMs = AtomicLong(0)

    // Topic-filter → callback registry. Used by [subscribe] to hold
    // listeners that survive reconnects: each call to `connect()`
    // re-applies them to the fresh HiveMQ client.
    private data class Subscription(
        val topicFilter: String,
        val callback: (topic: String, payload: ByteArray, isRetain: Boolean) -> Unit,
    )
    private val subscriptions = mutableListOf<Subscription>()
    private val subscriptionsLock = Any()

    val totalPublished: Long get() = publishCount.get()
    val lastPublishAtMillis: Long get() = lastPublishMs.get()

    fun isConnected(): Boolean = client?.state?.isConnected == true

    suspend fun connect(brokerUrl: String, username: String, password: String) {
        // Parse "tcp://host:1883" / "ssl://host:8883". Accept bare "host:1883".
        val (host, port, ssl) = parseBrokerUrl(brokerUrl)

        // Always tear down any prior client before building a new one. HiveMQ's
        // auto-reconnect keeps an old client alive in the background otherwise,
        // which would compete with the new one and confuse the connection state.
        disconnect()

        logBuffer.info(
            "mqtt connecting",
            mapOf("broker" to loggableUrl(brokerUrl), "ssl" to ssl, "auth" to username.isNotBlank()),
        )

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("pitstop-android-${UUID.randomUUID()}")
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addDisconnectedListener { ctx ->
                // HiveMQ MQTT3's automatic reconnect uses the BUILDER's
                // CONNECT defaults — NOT the credentials from the
                // original connectWith().send() call. Without re-applying
                // simpleAuth here, every auto-reconnect attempt sends
                // an unauthenticated CONNECT and Mosquitto returns
                // NOT_AUTHORIZED in a tight loop. Capture the active
                // username + password from this connect() invocation
                // via closure and reapply each retry.
                if (username.isNotBlank() && ctx is Mqtt3ClientDisconnectedContext) {
                    ctx.reconnector.connectWith()
                        .keepAlive(30)
                        .cleanSession(true)
                        .simpleAuth()
                            .username(username)
                            .password(password.toByteArray(Charsets.UTF_8))
                            .applySimpleAuth()
                        .applyConnect()
                }
                logBuffer.warn(
                    "mqtt disconnected",
                    mapOf(
                        "source" to ctx.source.toString(),
                        "cause" to (ctx.cause.message ?: ctx.cause::class.java.simpleName),
                        "reconnect" to ctx.reconnector.isReconnect,
                    ),
                )
            }
            .addConnectedListener {
                logBuffer.info("mqtt connected", mapOf("host" to host, "port" to port))
                // Re-apply any registered subscriptions whenever the
                // client (re)connects. HiveMQ's auto-reconnect resubs
                // active subs but if a subscription was registered
                // BEFORE the first connect, this is the path that
                // applies it on first connect; on auto-reconnect the
                // duplicate subscribe is a no-op server-side.
                applySubscriptionsLocked()
            }

        if (ssl) builder.sslWithDefaultConfig()

        val c = builder.buildAsync()
        client = c
        lastUrl = brokerUrl

        suspendCancellableCoroutine<Unit> { cont ->
            val connectBuilder = c.connectWith()
                .keepAlive(30)
                .cleanSession(true)
            if (username.isNotBlank()) {
                connectBuilder.simpleAuth()
                    .username(username)
                    .password(password.toByteArray(Charsets.UTF_8))
                    .applySimpleAuth()
            }
            connectBuilder.send()
                .whenComplete { _, t ->
                    if (t == null) {
                        // Connected-listener already logs the success; just resume here.
                        cont.resume(Unit)
                    } else {
                        logBuffer.error(
                            "mqtt connect failed",
                            mapOf("host" to host, "port" to port, "err" to (t.message ?: t::class.java.simpleName)),
                        )
                        cont.resumeWithException(
                            if (t is Mqtt3ConnAckException) t else RuntimeException(t.message, t),
                        )
                    }
                }
            cont.invokeOnCancellation { c.disconnect() }
        }
    }

    /**
     * Register a topic-filter subscription that survives reconnects.
     * Idempotent on the same `(topicFilter, callback)` reference; calling
     * twice with the same filter installs two callbacks (rare). The
     * callback fires on the HiveMQ flow thread — keep it cheap, push
     * heavy work to a coroutine on the caller's scope.
     */
    fun subscribe(
        topicFilter: String,
        callback: (topic: String, payload: ByteArray, isRetain: Boolean) -> Unit,
    ) {
        synchronized(subscriptionsLock) {
            subscriptions.add(Subscription(topicFilter, callback))
        }
        // Apply now if already connected; the connected-listener picks
        // it up on the next reconnect otherwise.
        val c = client
        if (c?.state?.isConnected == true) applyOneSubscription(c, topicFilter, callback)
    }

    private fun applySubscriptionsLocked() {
        val c = client ?: return
        val snapshot = synchronized(subscriptionsLock) { subscriptions.toList() }
        for (s in snapshot) applyOneSubscription(c, s.topicFilter, s.callback)
    }

    private fun applyOneSubscription(
        c: Mqtt3AsyncClient,
        topicFilter: String,
        callback: (String, ByteArray, Boolean) -> Unit,
    ) {
        c.subscribeWith()
            .topicFilter(topicFilter)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { msg ->
                runCatching {
                    val topic = msg.topic.toString()
                    val payload = msg.payloadAsBytes ?: ByteArray(0)
                    callback(topic, payload, msg.isRetain)
                }.onFailure { exc ->
                    logBuffer.warn(
                        "mqtt subscribe callback threw",
                        mapOf("topic_filter" to topicFilter, "err" to (exc.message ?: exc::class.java.simpleName)),
                    )
                }
            }
            .send()
            .whenComplete { ack, t ->
                if (t == null) {
                    logBuffer.info(
                        "mqtt subscribed",
                        mapOf("filter" to topicFilter, "qos" to (ack?.returnCodes?.firstOrNull()?.code ?: -1)),
                    )
                } else {
                    logBuffer.warn(
                        "mqtt subscribe failed",
                        mapOf("filter" to topicFilter, "err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
            }
    }

    /**
     * Returns true iff the message was handed off to the MQTT client
     * (i.e. there was a live connection). Callers (the bridge service)
     * use the false case to fall back to [OfflineBuffer].
     */
    fun publish(topic: String, payload: String): Boolean {
        val c = client ?: return false
        if (!c.state.isConnected) return false
        c.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(payload.toByteArray(Charsets.UTF_8))
            .retain(false)
            .send()
            .whenComplete { _, t ->
                if (t == null) {
                    publishCount.incrementAndGet()
                    lastPublishMs.set(System.currentTimeMillis())
                    logBuffer.debug(
                        "mqtt publish",
                        mapOf("topic" to topic, "bytes" to payload.length),
                    )
                } else {
                    logBuffer.warn(
                        "mqtt publish failed",
                        mapOf("topic" to topic, "err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
            }
        return true
    }

    fun disconnect() {
        client?.let {
            logBuffer.info("mqtt disconnecting")
            it.disconnect()
        }
        client = null
    }

    private data class Endpoint(val host: String, val port: Int, val ssl: Boolean)

    private fun parseBrokerUrl(url: String): Endpoint {
        val trimmed = url.trim()
        return try {
            if ("://" in trimmed) {
                val uri = URI(trimmed)
                val ssl = uri.scheme.equals("ssl", true) ||
                    uri.scheme.equals("mqtts", true) ||
                    uri.scheme.equals("tls", true)
                Endpoint(
                    host = uri.host ?: throw IllegalArgumentException("missing host: $url"),
                    port = if (uri.port != -1) uri.port else if (ssl) 8883 else 1883,
                    ssl = ssl,
                )
            } else {
                val parts = trimmed.split(":")
                Endpoint(
                    host = parts[0],
                    port = parts.getOrNull(1)?.toIntOrNull() ?: 1883,
                    ssl = false,
                )
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Bad broker URL: $url", e)
        }
    }
}
