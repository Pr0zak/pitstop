# Project-specific ProGuard / R8 rules.
# Release builds run R8 in full mode (isMinifyEnabled + isShrinkResources).
# These rules keep the reflection-heavy / serialization-driven surfaces
# that R8 can't see through statically.

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# ---- kotlinx.serialization ----
# The serialization plugin generates a synthetic Companion + $$serializer
# per @Serializable class. R8 strips them unless kept. These rules are the
# canonical set from the kotlinx.serialization README.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep every @Serializable class + its generated serializer in our model
# packages (DTOs round-trip through Retrofit + on-disk drive payloads).
-keep,includedescriptorclasses class com.pitstop.**$$serializer { *; }
-keepclassmembers class com.pitstop.** {
    *** Companion;
}
-keepclasseswithmembers class com.pitstop.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Retrofit + OkHttp ----
-keepattributes Signature, Exceptions
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Crash-report triage signal ----
# PitstopApp's uncaught-exception handler writes throwable::class.java.simpleName
# into the crash JSON shipped to /api/logs, and ~66 further sites use it as the
# `t.message ?: t::class.java.simpleName` fallback. R8 renamed the third-party
# exception classes, so the shipped "type" field was a single letter:
#     retrofit2.HttpException                        -> w6.o
#     kotlinx.serialization.SerializationException   -> T5.e
#     kotlinx.serialization.MissingFieldException    -> T5.a
#     kotlinx.serialization.json.internal.JsonDecodingException -> Z5.h
# getSimpleName() reads the dex type descriptor, and those descriptors were
# absent from the shipped dex entirely — so remote crash triage saw type="o".
# java.*/android.* exceptions were unaffected (R8 never renames them), which is
# exactly why this stayed invisible: the common crashes still read correctly.
#
# -keepnames = keep the NAME, still allow shrinking. No member retention, so
# the size cost is negligible.
-keepnames class * extends java.lang.Throwable

# ---- JCTools (lock-free queues, pulled in UNSHADED by hivemq-mqtt-client) ----
# Netty's PlatformDependent resolves these queue fields BY NAME through
# reflection to compute sun.misc.Unsafe field offsets. R8 renamed them, so
# MqttOutgoingQosHandler's static initializer threw
#   NoSuchFieldException: No field consumerIndex in class Ls6/b;
# wrapped as ExceptionInInitializerError, on EVERY MQTT connect attempt —
# an unrecoverable crash loop in the bridge service. Release builds only:
# the field names survive unminified, so debug never saw it.
#
# The existing `-keep class io.netty.**` does NOT cover this. Netty ships a
# shaded copy at io.netty.util.internal.shaded.org.jctools, but HiveMQ also
# depends on the real org.jctools artifact, and that is the one that was
# obfuscated. Keep member NAMES, not just the classes.
-keep class org.jctools.** { *; }
-keepclassmembernames class org.jctools.** { *; }
-dontwarn org.jctools.**

# ---- HiveMQ MQTT client + Netty transport ----
-keep class com.hivemq.client.** { *; }
-dontwarn org.slf4j.**
-dontwarn io.reactivex.rxjava3.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**
# Netty references optional dependencies (epoll, kqueue, etc.) by reflection.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.x509.**

# ---- Nordic BLE ----
-keep class no.nordicsemi.android.ble.** { *; }

# ---- Tink (security-crypto / EncryptedSharedPreferences) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**

# ---- MapLibre ----
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
