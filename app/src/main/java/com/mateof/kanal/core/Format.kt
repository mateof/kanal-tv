package com.mateof.kanal.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dates follow the language the interface is showing, which is not necessarily
 * the television's: someone with an English panel can read Kanal in Galician,
 * and "Thursday 30 de July" is nobody's language. [setFormatLocale] is called
 * as the interface resolves its language.
 */
private class Formats(val locale: Locale) {
    val clock = SimpleDateFormat("HH:mm", locale)
    val dayAndClock = SimpleDateFormat("EEE d MMM, HH:mm", locale)
    val dayShort = SimpleDateFormat("EEE d MMM", locale)

    // Galician and Spanish both read "xoves 30 de xullo"; English does not.
    val day = SimpleDateFormat(
        if (locale.language == "en") "EEEE d MMMM" else "EEEE d 'de' MMMM",
        locale
    )
}

@Volatile
private var target: Locale = Locale(AppLanguage.FALLBACK)

// SimpleDateFormat is not thread-safe and the guide formats dates off the main
// thread, so every thread keeps its own set and rebuilds it if the language
// changed underneath.
private val perThread = object : ThreadLocal<Formats>() {
    override fun initialValue(): Formats = Formats(target)
}

private fun formats(): Formats {
    val existing = perThread.get()
    if (existing != null && existing.locale == target) return existing
    return Formats(target).also { perThread.set(it) }
}

fun setFormatLocale(locale: Locale) {
    target = locale
}

fun formatClock(epochMillis: Long): String = formats().clock.format(Date(epochMillis))

fun formatDayAndClock(epochMillis: Long): String = formats().dayAndClock.format(Date(epochMillis))

fun formatDay(epochMillis: Long): String =
    formats().day.format(Date(epochMillis)).replaceFirstChar { it.uppercase() }

/** "xov 30 xul" — short enough for a row of day chips. */
fun formatDayShort(epochMillis: Long): String =
    formats().dayShort.format(Date(epochMillis)).replaceFirstChar { it.uppercase() }.replace(".", "")

/** "1 h 45 min" / "45 min" / "30 s". */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return ""
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
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
