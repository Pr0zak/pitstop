package com.pitstop.di

import com.pitstop.http.PitstopApi
import com.pitstop.http.PitstopAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
        authInterceptor: PitstopAuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
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
