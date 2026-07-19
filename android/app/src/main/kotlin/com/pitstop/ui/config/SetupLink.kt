package com.pitstop.ui.config

import android.net.Uri

/**
 * Parsed contents of a `pitstop://setup?…` handoff link (or the same as an
 * `https://<host>/setup?…` App Link). Lets a user move their whole connection
 * config — server URL, tokens, vehicle, optional MQTT — onto the phone by
 * pasting one string or tapping one link, instead of thumb-typing two 40-char
 * tokens on a phone keyboard (the friction this whole UX pass is about).
 *
 * Every field is optional so a partial link (eg. just server + query token)
 * still imports what it carries. Query-param names accept a couple of aliases
 * so a hand-written link is forgiving.
 */
data class SetupPayload(
    val apiBaseUrl: String? = null,
    val queryToken: String? = null,
    val ingestToken: String? = null,
    val vehicleSlug: String? = null,
    val brokerUrl: String? = null,
    val mqttUser: String? = null,
    val mqttPassword: String? = null,
) {
    /** A payload that carries nothing usable isn't worth importing. */
    val isEmpty: Boolean
        get() = listOf(
            apiBaseUrl, queryToken, ingestToken, vehicleSlug,
            brokerUrl, mqttUser, mqttPassword,
        ).all { it.isNullOrBlank() }

    /** Human-readable list of what a successful import will set, for the toast. */
    fun importedFields(): List<String> = buildList {
        if (!apiBaseUrl.isNullOrBlank()) add("server")
        if (!queryToken.isNullOrBlank()) add("query token")
        if (!ingestToken.isNullOrBlank()) add("ingest token")
        if (!vehicleSlug.isNullOrBlank()) add("vehicle")
        if (!brokerUrl.isNullOrBlank()) add("broker")
        if (!mqttUser.isNullOrBlank()) add("MQTT user")
        if (!mqttPassword.isNullOrBlank()) add("MQTT password")
    }
}

/** Outcome of trying to import a pasted string / tapped link. */
sealed interface SetupImportResult {
    data class Ok(val payload: SetupPayload) : SetupImportResult
    /** Not a pitstop setup link at all (wrong scheme / not a URI). */
    object NotASetupLink : SetupImportResult
    /** Recognised as a setup link but it carried no usable fields. */
    object Empty : SetupImportResult
}

object SetupLink {

    private const val SCHEME = "pitstop"
    private const val HOST = "setup"
    private const val PATH = "/setup"

    /**
     * Parse a raw pasted/linked string. Accepts:
     *  - `pitstop://setup?server=…&query=…&ingest=…&vehicle=…`
     *  - `https://<host>/setup?…` (App Link form)
     * Returns [SetupImportResult.NotASetupLink] for anything else so the caller
     * can tell "you pasted junk" from "you pasted an empty link".
     */
    fun parse(raw: String): SetupImportResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return SetupImportResult.NotASetupLink
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return SetupImportResult.NotASetupLink

        val looksLikeSetup = when {
            uri.scheme.equals(SCHEME, ignoreCase = true) &&
                (uri.host.equals(HOST, ignoreCase = true) || uri.host.isNullOrEmpty()) -> true
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                uri.path?.trimEnd('/').equals(PATH, ignoreCase = true) -> true
            else -> false
        }
        if (!looksLikeSetup) return SetupImportResult.NotASetupLink

        // Uri.getQueryParameter URL-decodes for us. Try each alias in order.
        fun param(vararg names: String): String? =
            names.asSequence()
                .mapNotNull { runCatching { uri.getQueryParameter(it) }.getOrNull() }
                .firstOrNull { !it.isNullOrBlank() }
                ?.trim()

        val payload = SetupPayload(
            apiBaseUrl = param("server", "url", "api", "base_url"),
            queryToken = param("query", "qtoken", "query_token", "q"),
            ingestToken = param("ingest", "itoken", "ingest_token", "i"),
            vehicleSlug = param("vehicle", "slug", "v"),
            brokerUrl = param("broker", "mqtt", "broker_url"),
            mqttUser = param("mqtt_user", "user", "mu"),
            mqttPassword = param("mqtt_pass", "mqtt_password", "pass", "mp"),
        )
        return if (payload.isEmpty) SetupImportResult.Empty else SetupImportResult.Ok(payload)
    }
}
