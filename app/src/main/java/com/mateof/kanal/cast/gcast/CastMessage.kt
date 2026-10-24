package com.mateof.kanal.cast.gcast

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The one protobuf message of the Cast protocol (CASTV2), written by hand:
 * six fields are not worth a protobuf runtime and its code generation.
 *
 * ```
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;          // STRING = 0
 *   optional string payload_utf8 = 6;
 *   optional bytes payload_binary = 7;
 * }
 * ```
 *
 * On the wire each one goes after its length, as four big-endian bytes.
 */
data class CastMessage(
    val source: String,
    val destination: String,
    val namespace: String,
    val payload: String
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.varintField(1, 0)
        out.stringField(2, source)
        out.stringField(3, destination)
        out.stringField(4, namespace)
        out.varintField(5, 0)
        out.stringField(6, payload)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            var source = ""
            var destination = ""
            var namespace = ""
            var payload = ""
            var at = 0

            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (at >= bytes.size) throw IOException("mensaje de Cast cortado")
                    val b = bytes[at++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("varint demasiado largo")
                }
            }

            while (at < bytes.size) {
                val key = varint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val length = varint().toInt()
                        if (length < 0 || at + length > bytes.size) throw IOException("campo de Cast cortado")
                        val text = String(bytes, at, length, Charsets.UTF_8)
                        at += length
                        when (field) {
                            2 -> source = text
                            3 -> destination = text
                            4 -> namespace = text
                            6 -> payload = text
                        }
                    }
                    5 -> at += 4
                    1 -> at += 8
                    else -> throw IOException("tipo de campo desconocido en un mensaje de Cast")
                }
            }
            return CastMessage(source, destination, namespace, payload)
        }

        private fun ByteArrayOutputStream.varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            write(v.toInt())
        }

        private fun ByteArrayOutputStream.varintField(field: Int, value: Long) {
            varint((field shl 3).toLong())
            varint(value)
        }

        private fun ByteArrayOutputStream.stringField(field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            varint(((field shl 3) or 2).toLong())
            varint(bytes.size.toLong())
            write(bytes)
        }
    }
}
