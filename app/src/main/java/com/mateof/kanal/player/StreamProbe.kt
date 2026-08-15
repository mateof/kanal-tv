package com.mateof.kanal.player

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.net.redactUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** How much of the body is worth looking at to tell what it is. */
private const val PROBE_BYTES = 2048L

/** Transport stream packets all begin with this, every 188 bytes. */
private const val TS_SYNC = 0x47.toByte()

/**
 * Asks a stream that would not play what it actually sent.
 *
 * "No recognisable format" covers two very different situations, and the player
 * cannot tell them apart: a panel answering 200 with an error page or an empty
 * body, and a real stream in a container nothing here can read. The first is a
 * channel that is down or an account at its connection limit — nothing to fix
 * in the app — and the second is a gap worth closing. Guessing between them
 * from an error code has already cost more than one wrong diagnosis, so this
 * fetches the first couple of kilobytes and writes down what came back.
 */
@Singleton
class StreamProbe @Inject constructor(
    private val http: HttpProvider,
    private val logger: FileLogger
) {

    /**
     * @return a short phrase for the screen, or null when nothing useful came
     *   of it. The detail always goes to the log either way.
     */
    suspend fun describe(url: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        val safeUrl = redactUrl(url)
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                // Panels that stream forever would otherwise never finish.
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()

            http.client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val body = response.body?.source()?.let { source ->
                    source.request(PROBE_BYTES)
                    source.buffer.snapshot(minOf(PROBE_BYTES, source.buffer.size).toInt())
                }?.toByteArray() ?: ByteArray(0)

                val summary = summarise(body)
                logger.i(
                    "Probe",
                    "$safeUrl → ${response.code}, tipo '$type', ${body.size} B leídos: $summary"
                )
                if (body.isNotEmpty()) {
                    logger.d("Probe", "Primeros bytes: ${preview(body)}")
                }
                verdict(response.code, type, body)
            }
        } catch (e: Exception) {
            logger.w("Probe", "No se pudo examinar $safeUrl", e)
            null
        }
    }

    private fun summarise(body: ByteArray): String = when {
        body.isEmpty() -> "cuerpo vacío"
        looksLikeTs(body) -> "parece MPEG-TS"
        body[0] == '<'.code.toByte() -> "parece HTML o XML"
        body[0] == '{'.code.toByte() || body[0] == '['.code.toByte() -> "parece JSON"
        body.copyOfRange(0, minOf(7, body.size))
            .toString(Charsets.US_ASCII) == "#EXTM3U" -> "parece una lista M3U"

        else -> "binario sin reconocer"
    }

    /**
     * A transport stream starts with a sync byte and repeats it every 188. The
     * first one is allowed to be some way in: a stream that only *starts* late
     * is precisely the case worth reporting, since it plays elsewhere.
     */
    private fun looksLikeTs(body: ByteArray): Boolean {
        val first = body.indexOfFirst { it == TS_SYNC }
        if (first < 0) return false
        var at = first
        var packets = 0
        while (at + 188 < body.size) {
            if (body[at] != TS_SYNC) return false
            at += 188
            packets++
        }
        return packets >= 2
    }

    /** The line the user sees under the error, when there is something to say. */
    private fun verdict(code: Int, type: String, body: ByteArray): String? = when {
        code !in 200..299 -> "El servidor respondió $code."
        body.isEmpty() -> "El servidor aceptó la conexión pero no envió nada."
        body[0] == '<'.code.toByte() || body[0] == '{'.code.toByte() ->
            "El servidor devolvió un mensaje de texto en lugar de vídeo."

        looksLikeTs(body) -> null
        type.startsWith("text/") -> "El servidor devolvió texto en lugar de vídeo."
        else -> null
    }

    /** First bytes as hex, with the printable ones alongside. */
    private fun preview(body: ByteArray): String {
        val take = body.copyOfRange(0, minOf(32, body.size))
        val hex = take.joinToString(" ") { "%02x".format(it) }
        val text = take.map { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() || c in " .,:/<>{}[]\"'-_=") c else '·'
        }.joinToString("")
        return "$hex  |$text|"
    }
}
