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
