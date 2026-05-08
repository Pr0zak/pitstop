# Project-specific ProGuard rules.

# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# HiveMQ MQTT client
-keep class com.hivemq.client.** { *; }
-dontwarn org.slf4j.**
-dontwarn io.reactivex.rxjava3.**

# Nordic BLE
-keep class no.nordicsemi.android.ble.** { *; }
