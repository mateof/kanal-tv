package com.mateof.kanal.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient readers for Xtream responses.
 *
 * Panels are wildly inconsistent: the same field comes back as `1`, `"1"`,
 * `true` or `null` depending on the implementation, empty collections are
 * sometimes `[]` and sometimes `{}` or `""`, and missing keys are the norm.
 * Decoding through typed DTOs means one bad field kills a whole 40 000-item
 * response, so everything is read defensively from the raw tree instead.
 */

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

fun JsonElement?.asArrayOrEmpty(): List<JsonElement> = when (this) {
    is JsonArray -> this
    // Some panels return an object keyed by index instead of an array.
    is JsonObject -> values.toList()
    else -> emptyList()
}

private fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject.str(key: String, fallback: String = ""): String {
    val p = prim(key) ?: return fallback
    val value = p.content
    return if (value.isEmpty() || value == "null") fallback else value
}

fun JsonObject.int(key: String, fallback: Int = 0): Int =
    prim(key)?.content?.trim()?.toIntOrNull() ?: fallback

fun JsonObject.long(key: String, fallback: Long = 0L): Long =
    prim(key)?.content?.trim()?.toLongOrNull() ?: fallback

fun JsonObject.double(key: String, fallback: Double = 0.0): Double =
    prim(key)?.content?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: fallback

/** Accepts `1`, `"1"`, `true`, `"true"`, `"yes"`. */
fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val raw = prim(key)?.content?.trim()?.lowercase() ?: return fallback
    return raw == "1" || raw == "true" || raw == "yes"
}

fun JsonObject.array(key: String): List<JsonElement> = this[key].asArrayOrEmpty()

/** First non-empty value among [keys]; panels rename fields freely. */
fun JsonObject.firstStr(vararg keys: String, fallback: String = ""): String {
    for (key in keys) {
        val value = str(key)
        if (value.isNotEmpty()) return value
    }
    return fallback
}

fun JsonObject.firstLong(vararg keys: String, fallback: Long = 0L): Long {
    for (key in keys) {
        val value = long(key, Long.MIN_VALUE)
        if (value != Long.MIN_VALUE) return value
    }
    return fallback
}
