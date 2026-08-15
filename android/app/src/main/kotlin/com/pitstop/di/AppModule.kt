package com.pitstop.di

import android.content.Context
import com.pitstop.http.CacheRewriteInterceptor
import com.pitstop.http.DriveUploadProgressInterceptor
import com.pitstop.http.OfflineCacheInterceptor
import com.pitstop.http.PitstopApi
import com.pitstop.http.PitstopAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        authInterceptor: PitstopAuthInterceptor,
        offlineCacheInterceptor: OfflineCacheInterceptor,
        cacheRewriteInterceptor: CacheRewriteInterceptor,
        driveUploadProgressInterceptor: DriveUploadProgressInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        // 50 MB on-disk HTTP cache (CACHE-1). Every successful GET goes
        // through cacheRewriteInterceptor (network layer) which forces
        // a cacheable Cache-Control header, then OfflineCacheInterceptor
        // (application layer) serves it back when the network is
        // unreachable. Writes are passed through untouched.
        .cache(Cache(File(context.cacheDir, "pitstop-http"), 50L * 1024L * 1024L))
        .addInterceptor(offlineCacheInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .addNetworkInterceptor(cacheRewriteInterceptor)
        // Byte-level progress for drive uploads. Network layer, so it
        // wraps the body actually written to the socket and a retry
        // restarts the count instead of resuming a stale one.
        .addNetworkInterceptor(driveUploadProgressInterceptor)
        // Read-only GETs back the pull-to-refresh spinners (Home, History,
        // Heatmap). Cap their read timeout well under the 60 s upload
        // timeout below so a slow/stalled endpoint can't leave the refresh
        // arrow spinning for the better part of a minute — the call fails
        // fast, the ViewModel's runCatching clears its loading flag, and the
        // spinner retracts. POST uploads keep the full 60 s read window.
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                chain.withConnectTimeout(8, TimeUnit.SECONDS)
                    .withReadTimeout(20, TimeUnit.SECONDS)
                    .proceed(request)
            } else {
                chain.proceed(request)
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        // Drive uploads can carry multi-MB JSON payloads (20k+ frames is
        // routine) and the server holds the request while writing the
        // hypertable inserts. The previous 20 s read + 10 s write defaults
        // were tripping on long drives — the server completed the upload
        // but the client gave up reading the response, then retried and
        // the next request came back with duplicate=true once the server
        // re-acked the existing row. Looks like "Sync failed" to the user
        // but the data was already in. Bumped to 60 s on both axes so the
        // happy-path completes inside the timeout.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/") // placeholder — interceptor rewrites
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePitstopApi(retrofit: Retrofit): PitstopApi =
        retrofit.create(PitstopApi::class.java)
}
