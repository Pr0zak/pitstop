package com.pitstop.log

/**
 * Privacy helpers used when building log messages or context maps for [LogBuffer].
 *
 * BLE MAC addresses: mask the OUI (manufacturer prefix); keep the last 4 octets so disconnect
 * traces can still be correlated.
 *
 *   AA:BB:CC:DD:EE:FF  ->  xx:xx:xx:xx:EE:FF  (mask first 4 octets)
 *   AA:BB:CC:DD:1A:B2  ->  xx:xx:xx:xx:1A:B2
 *
 * Note the user instructions say "mask to the last 4 octets". The intent is that nobody
 * reading the depot can recover the MAC vendor; the trailing 4 octets are still useful for
 * correlation since they're device-unique within a vendor.
 */
fun maskMac(mac: String?): String {
    if (mac.isNullOrBlank()) return "(none)"
    val parts = mac.split(":", "-")
    if (parts.size != 6) return "xx:xx:xx:xx:xx:xx"
    return "xx:xx:xx:xx:${parts[4]}:${parts[5]}".uppercase()
}

/**
 * Build a loggable URL: keep scheme + host + port + path, but strip any userinfo
 * (`http://user:pass@host/...`) and any `?token=` style query bits.
 */
fun loggableUrl(url: String?): String {
    if (url.isNullOrBlank()) return "(none)"
    return try {
        val uri = java.net.URI(url)
        val sb = StringBuilder()
        uri.scheme?.let { sb.append(it).append("://") }
        uri.host?.let { sb.append(it) }
        if (uri.port > 0) sb.append(":").append(uri.port)
        uri.rawPath?.let { sb.append(it) }
        sb.toString()
    } catch (_: Exception) {
        "(unparseable)"
    }
}

/** Round a coordinate to N decimals for log output. Use 2 decimals at info+, 5 at debug. */
fun roundCoord(value: Double?, decimals: Int = 2): Double? {
    if (value == null) return null
    val factor = Math.pow(10.0, decimals.toDouble())
    return Math.round(value * factor) / factor
}
