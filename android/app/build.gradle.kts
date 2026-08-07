// Inside an `android { }` block the Kotlin DSL resolves bare `java` to
// Gradle's java extension, not the JDK package — so these must be
// top-level imports rather than fully-qualified references below.
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pitstop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pitstop"
        minSdk = 26
        targetSdk = 35
        // CI passes BUILD_VERSION_NAME (from the v* tag) and BUILD_VERSION_CODE.
        // Local builds keep safe defaults.
        //
        // versionCode ranges, kept disjoint on purpose:
        //   1..999    GitHub Actions run number — the sideloaded debug APKs
        //   1000+     Play uploads (1000 + run number once CI builds them)
        // Play requires versionCode to be unique and strictly increasing per
        // upload FOREVER; reusing one is a hard rejection. Keeping Play in its
        // own range means the debug stream can never burn a number Play needs.
        versionCode = System.getenv("BUILD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("BUILD_VERSION_NAME") ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release/upload signing. Resolved from, in order:
    //   1. android/keystore.properties or ~/.pitstop-keys/keystore.properties
    //   2. ANDROID_KEYSTORE_* environment variables (for CI)
    // Absent both, `release` stays unsigned and bundleRelease still builds —
    // so a fork or a fresh clone is never blocked on a secret it cannot have.
    //
    // NOTHING here may be committed: *.jks, *.keystore and keystore.properties
    // are all gitignored. This is the UPLOAD key, not the app signing key —
    // Play App Signing holds the latter, so losing this one is recoverable
    // via a Play Console upload-key reset, but it should still be backed up.
    val keystoreProps = Properties().apply {
        val candidates = listOf(
            rootProject.file("keystore.properties"),
            File(System.getProperty("user.home"), ".pitstop-keys/keystore.properties"),
        )
        candidates.firstOrNull { it.exists() }?.let { load(it.inputStream()) }
    }
    fun signingValue(propKey: String, envKey: String): String? =
        keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

    val releaseStorePath = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
    val hasReleaseSigning = releaseStorePath != null && File(releaseStorePath).exists()

    signingConfigs {
        // Stable, repo-committed debug keystore so APKs from successive
        // CI runs (and local dev builds) all share the same fingerprint
        // and Android accepts in-place upgrades. Without this, every CI
        // runner generates its own debug.keystore and Android refuses
        // to upgrade across signers ("app not installed"). Debug-only —
        // no real secret value.
        getByName("debug") {
            storeFile = rootProject.file("keystores/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseStorePath!!)
                storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8 full-mode minify + resource shrink. material-icons-extended,
            // MapLibre, and Netty (HiveMQ transport) bloat the unminified APK
            // to ~72 MB. Keep rules for reflection-heavy deps live in
            // proguard-rules.pro. NOTE: no release signing config is wired
            // here (CI injects the keystore), so assembleRelease must be
            // validated in CI / with a signing config present.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
        named("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.livedata.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager — runs the periodic update-check worker
    implementation(libs.work.runtime)

    // Room — local SQLite queue for pending drive uploads (#117)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (EncryptedFile / EncryptedSharedPreferences via Tink)
    implementation(libs.security.crypto)

    // MQTT
    implementation(libs.hivemq.mqtt.client)

    // BLE — Nordic library
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)

    // GPS
    implementation(libs.play.services.location)

    // MapLibre — route polyline on the trip-detail screen (#122).
    // Same tile + style vocab as the web frontend, so a "voyager"
    // style URL renders identically across surfaces.
    implementation(libs.maplibre.android)

    // Android Auto (AndroidX Car App library)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
