package com.mateof.kanal.data.epg

import android.util.Xml
import com.mateof.kanal.core.log.Klog
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class XmltvChannel(val id: String, val displayNames: List<String>, val icon: String)

data class XmltvProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
    val category: String
)

/**
 * Streaming XMLTV reader.
 *
 * A week of guide for a big provider is tens of megabytes, so nothing is held
 * in memory: channels and programmes are pushed to the caller as they are read
 * and the caller decides what to keep.
 */
class XmltvParser {

    fun parse(
        input: InputStream,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (XmltvProgramme) -> Unit
    ) {
        val stream = maybeGunzip(input)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var channelId = ""
        var displayNames = mutableListOf<String>()
        var icon = ""

        var progChannel = ""
        var progStart = 0L
        var progStop = 0L
        var title = ""
        var description = ""
        var category = ""

        var inChannel = false
        var inProgramme = false
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name.lowercase()) {
                        "channel" -> {
                            inChannel = true
                            channelId = parser.getAttributeValue(null, "id").orEmpty().trim()
                            displayNames = mutableListOf()
                            icon = ""
                        }

                        "programme" -> {
                            inProgramme = true
                            progChannel = parser.getAttributeValue(null, "channel").orEmpty().trim()
                            progStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            progStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                            description = ""
                            category = ""
                        }

                        "icon" -> if (inChannel && icon.isEmpty()) {
                            icon = parser.getAttributeValue(null, "src").orEmpty()
                        }
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (parser.name.lowercase()) {
                        "display-name" -> if (inChannel && value.isNotEmpty()) displayNames += value
                        "title" -> if (inProgramme && title.isEmpty()) title = value
                        "desc" -> if (inProgramme && description.isEmpty()) description = value
                        "category" -> if (inProgramme && category.isEmpty()) category = value
                        "channel" -> {
                            if (inChannel && channelId.isNotEmpty()) {
                                onChannel(XmltvChannel(channelId, displayNames.toList(), icon))
                            }
                            inChannel = false
                        }

                        "programme" -> {
                            if (inProgramme && progChannel.isNotEmpty() && progStart > 0 && progStop > progStart) {
                                onProgramme(
                                    XmltvProgramme(
                                        channelId = progChannel,
                                        start = progStart,
                                        stop = progStop,
                                        title = title.ifBlank { "Sin título" },
                                        description = description,
                                        category = category
                                    )
                                )
                            }
                            inProgramme = false
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
    }

    /** Providers serve `.xml`, `.xml.gz` and gzipped `.php` interchangeably. */
    private fun maybeGunzip(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input, 2)
        val signature = ByteArray(2)
        val read = pushback.read(signature)
        if (read > 0) pushback.unread(signature, 0, read)
        val isGzip = read == 2 &&
            (signature[0].toInt() and 0xFF) == 0x1F &&
            (signature[1].toInt() and 0xFF) == 0x8B
        return if (isGzip) GZIPInputStream(pushback, 32 * 1024) else pushback
    }
}

/**
 * XMLTV timestamps look like `20260728120000 +0200`; the offset is optional and
 * some providers omit it, in which case UTC is the least surprising reading.
 */
fun parseXmltvTime(raw: String?): Long {
    val value = raw?.trim().orEmpty()
    if (value.length < 14) return 0L
    return try {
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        val hour = value.substring(8, 10).toInt()
        val minute = value.substring(10, 12).toInt()
        val second = value.substring(12, 14).toInt()

        val offsetPart = value.substring(14).trim()
        val offsetMillis = if (offsetPart.length >= 5) {
            val sign = if (offsetPart[0] == '-') -1 else 1
            val digits = offsetPart.drop(1)
            val hours = digits.substring(0, 2).toInt()
            val minutes = digits.substring(2, 4).toInt()
            sign * ((hours * 60 + minutes) * 60_000L)
        } else {
            0L
        }

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        calendar.timeInMillis - offsetMillis
    } catch (e: Exception) {
        Klog.w("Xmltv", "Fecha ilegible: '$value'")
        0L
    }
}
