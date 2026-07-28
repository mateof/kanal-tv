package com.mateof.kanal.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Every user-facing string in this app is Spanish, so the dates are too. Using
 * the device locale produced "Thursday 30 de July" on an English television.
 */
private val SPANISH = Locale("es", "ES")

private val clock = SimpleDateFormat("HH:mm", SPANISH)
private val dayAndClock = SimpleDateFormat("EEE d MMM, HH:mm", SPANISH)
private val day = SimpleDateFormat("EEEE d 'de' MMMM", SPANISH)
private val dayShort = SimpleDateFormat("EEE d MMM", SPANISH)

fun formatClock(epochMillis: Long): String = clock.format(Date(epochMillis))

fun formatDayAndClock(epochMillis: Long): String = dayAndClock.format(Date(epochMillis))

fun formatDay(epochMillis: Long): String =
    day.format(Date(epochMillis)).replaceFirstChar { it.uppercase() }

/** "jue 30 jul" — short enough for a row of day chips. */
fun formatDayShort(epochMillis: Long): String =
    dayShort.format(Date(epochMillis)).replaceFirstChar { it.uppercase() }.replace(".", "")

/** "1 h 45 min" / "45 min" / "30 s". */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return ""
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return when {
        hours > 0 -> "${hours} h ${minutes} min"
        minutes > 0 -> "$minutes min"
        else -> "${TimeUnit.MILLISECONDS.toSeconds(millis)} s"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}

/**
 * Sort key that ignores leading channel numbering ("101. La 1", "|ES| La 1")
 * and accents, so alphabetical order matches what the user sees.
 */
fun sortKeyOf(name: String): String {
    val cleaned = name.trim()
        .removePrefix("|").substringAfterLast('|')
        .trimStart(' ', '-', '.', ':', '#')
        .dropWhile { it.isDigit() }
        .trimStart(' ', '-', '.', ':', ')')
    return (cleaned.ifBlank { name }).lowercase(Locale.getDefault()).stripAccents()
}

fun String.stripAccents(): String {
    val from = "áàäâãéèëêíìïîóòöôõúùüûñç"
    val to = "aaaaaeeeeiiiiooooouuuunc"
    val sb = StringBuilder(length)
    for (c in this) {
        val i = from.indexOf(c)
        sb.append(if (i >= 0) to[i] else c)
    }
    return sb.toString()
}

/** Loose match used by the search box: accent- and case-insensitive. */
fun String.normalizedForSearch(): String = lowercase(Locale.getDefault()).stripAccents()
