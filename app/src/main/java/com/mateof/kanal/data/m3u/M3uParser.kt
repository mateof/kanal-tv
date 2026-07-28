package com.mateof.kanal.data.m3u

import java.io.BufferedReader

enum class M3uKind { LIVE, MOVIE, SERIES }

data class M3uEntry(
    val url: String,
    val name: String,
    val logo: String = "",
    val group: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val channelNumber: Int = 0,
    val catchupDays: Int = 0,
    val userAgent: String = "",
    val kind: M3uKind = M3uKind.LIVE
)

/**
 * Streaming M3U/M3U8 reader.
 *
 * Playlists routinely carry 40 000+ entries, so the file is consumed line by
 * line and each entry handed straight to the caller instead of building a list
 * that then gets copied into the database.
 */
class M3uParser {

    /** @return the guide url advertised in the `#EXTM3U` header, if any. */
    fun parse(reader: BufferedReader, onEntry: (M3uEntry) -> Unit): String {
        var epgUrl = ""
        var pending: Pending? = null
        var lineNumber = 0

        reader.forEachLine { rawLine ->
            lineNumber++
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributesOf(line)
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"].orEmpty())
                        .split(',')
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                        .orEmpty()
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val group = line.substringAfter(':', "").trim()
                    if (group.isNotEmpty()) pending = pending?.copy(group = group)
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val option = line.substringAfter(':', "").trim()
                    if (option.startsWith("http-user-agent=", ignoreCase = true)) {
                        pending = pending?.copy(userAgent = option.substringAfter('=').trim())
                    }
                }

                line.startsWith("#") -> Unit // KODIPROP, EXTVLCOPT variants, comments…

                else -> {
                    val current = pending
                    pending = null
                    if (current != null && looksLikeUrl(line)) {
                        onEntry(current.toEntry(line))
                    } else if (looksLikeUrl(line)) {
                        // A bare url with no #EXTINF still deserves an entry.
                        onEntry(M3uEntry(url = line, name = line.substringAfterLast('/'), kind = kindOf(line)))
                    }
                }
            }
        }
        return epgUrl
    }

    private data class Pending(
        val name: String,
        val logo: String,
        val group: String,
        val tvgId: String,
        val tvgName: String,
        val channelNumber: Int,
        val catchupDays: Int,
        val userAgent: String
    ) {
        fun toEntry(url: String) = M3uEntry(
            url = url,
            name = name,
            logo = logo,
            group = group,
            tvgId = tvgId,
            tvgName = tvgName,
            channelNumber = channelNumber,
            catchupDays = catchupDays,
            userAgent = userAgent,
            kind = kindOf(url)
        )
    }

    private fun parseExtInf(line: String): Pending {
        val attrs = attributesOf(line)
        // The display name is everything after the last comma that is not inside
        // an attribute value.
        val name = line.substringAfterLast(',').trim().ifBlank {
            attrs["tvg-name"].orEmpty()
        }
        return Pending(
            name = name,
            logo = attrs["tvg-logo"] ?: attrs["logo"].orEmpty(),
            group = attrs["group-title"] ?: attrs["group"].orEmpty(),
            tvgId = attrs["tvg-id"] ?: attrs["tvg-chno-id"].orEmpty(),
            tvgName = attrs["tvg-name"].orEmpty(),
            channelNumber = (attrs["tvg-chno"] ?: attrs["channel-number"])?.toIntOrNull() ?: 0,
            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
            userAgent = attrs["user-agent"].orEmpty()
        )
    }

    private fun attributesOf(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun looksLikeUrl(line: String): Boolean =
        line.startsWith("http://", true) || line.startsWith("https://", true) ||
            line.startsWith("rtmp", true) || line.startsWith("rtsp", true) ||
            line.startsWith("udp", true) || line.startsWith("file://", true)

    private companion object {
        val ATTRIBUTE = Regex("""([A-Za-z0-9\-_]+)\s*=\s*"([^"]*)"""")
    }
}

/**
 * An `m3u_plus` export from an Xtream panel mixes live, films and episodes; the
 * path segment is what tells them apart.
 */
private fun kindOf(url: String): M3uKind = when {
    url.contains("/movie/", ignoreCase = true) -> M3uKind.MOVIE
    url.contains("/series/", ignoreCase = true) -> M3uKind.SERIES
    else -> M3uKind.LIVE
}

/** "Breaking Bad S01 E02" → show, season, episode. */
private val EPISODE_PATTERN =
    Regex("""^(.*?)[\s\-_.]*[Ss](\d{1,3})[\s\-_.]*[EeXx](\d{1,4})\b(.*)$""")

data class ParsedEpisode(val show: String, val season: Int, val episode: Int, val title: String)

fun parseEpisodeName(name: String): ParsedEpisode? {
    val match = EPISODE_PATTERN.find(name.trim()) ?: return null
    val show = match.groupValues[1].trim().trim('-', '.', '_', ' ')
    if (show.isEmpty()) return null
    return ParsedEpisode(
        show = show,
        season = match.groupValues[2].toIntOrNull() ?: 1,
        episode = match.groupValues[3].toIntOrNull() ?: 0,
        title = match.groupValues[4].trim().trim('-', '.', '_', ' ')
    )
}
