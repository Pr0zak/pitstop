package com.pitstop.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
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
 * Connection model: one client per process. The bridge service holds it and reconnects
 * on its own backoff schedule. We do NOT use the HiveMQ "automatic reconnect" feature
 * because the bridge wants to coordinate BLE liveness with broker liveness.
 */
@Singleton
class MqttPublisher @Inject constructor() {

    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var lastUrl: String? = null
    private val publishCount = AtomicLong(0)
    private val lastPublishMs = AtomicLong(0)

    val totalPublished: Long get() = publishCount.get()
    val lastPublishAtMillis: Long get() = lastPublishMs.get()

    fun isConnected(): Boolean = client?.state?.isConnected == true

    suspend fun connect(brokerUrl: String, username: String, password: String) {
        // Parse "tcp://host:1883" / "ssl://host:8883". Accept bare "host:1883".
        val (host, port, ssl) = parseBrokerUrl(brokerUrl)

        // Disconnect existing client if URL changed.
        if (lastUrl != brokerUrl) disconnect()

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("pitstop-android-${UUID.randomUUID()}")
            .serverHost(host)
            .serverPort(port)

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
                    if (t == null) cont.resume(Unit)
                    else cont.resumeWithException(
                        if (t is Mqtt3ConnAckException) t else RuntimeException(t.message, t),
                    )
                }
            cont.invokeOnCancellation { c.disconnect() }
        }
    }

    fun publish(topic: String, payload: String) {
        val c = client ?: return
        if (!c.state.isConnected) return
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
                }
            }
    }

    fun disconnect() {
        client?.disconnect()
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
