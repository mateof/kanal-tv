package com.mateof.kanal.data.net

import com.mateof.kanal.core.log.FileLogger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Hides credentials before a url reaches the log file. */
fun redactUrl(url: String): String =
    url.replace(CREDENTIAL_QUERY) { "${it.groupValues[1]}=***" }
        .replace(CREDENTIAL_PATH) { "/${it.groupValues[1]}/***/***/" }

private val CREDENTIAL_QUERY = Regex("(username|password|user|pass)=([^&]*)", RegexOption.IGNORE_CASE)
private val CREDENTIAL_PATH = Regex("/(live|movie|series|timeshift)/[^/]+/[^/]+/")

/**
 * Records every request in the diagnostic log. Verbose mode adds the response
 * size; it stays off by default because a playlist sync is thousands of calls.
 */
class TraceInterceptor(
    private val logger: FileLogger,
    private val verbose: () -> Boolean
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()
        val url = redactUrl(request.url.toString())
        return try {
            val response = chain.proceed(request)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!response.isSuccessful) {
                logger.w("Http", "${request.method} $url → ${response.code} (${ms} ms)")
            } else if (verbose()) {
                logger.d("Http", "${request.method} $url → ${response.code} (${ms} ms)")
            }
            response
        } catch (e: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            logger.e("Http", "${request.method} $url falló tras ${ms} ms", e)
            throw e
        }
    }
}

/**
 * Single OkHttp instance shared by the API, the playlist/EPG downloads, the
 * image loader and the player, so they pool connections instead of each
 * opening their own sockets to the same host.
 */
@Singleton
class HttpProvider @Inject constructor(
    private val logger: FileLogger
) {
    @Volatile
    var verboseLogging: Boolean = false

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(TraceInterceptor(logger) { verboseLogging })
            .build()
    }

    /** Longer read timeout: some panels take minutes to render a big playlist. */
    val longRunningClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
