package com.mateof.kanal.cast.gcast

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/** A service instance as mDNS described it. */
data class MdnsService(
    val instance: String,
    val address: String,
    val port: Int,
    val txt: Map<String, String>
)

/**
 * The smallest mDNS browser that finds a Chromecast: one question, asked from
 * an ordinary port.
 *
 * A query that does not come from port 5353 is a "legacy unicast" one, and
 * responders have to answer it straight to the sender (RFC 6762 §6.7). That
 * spares listening on the multicast group, which on Android needs a
 * MulticastLock and shares port 5353 with the system's own responder. NsdManager
 * is not used either: before API 34 it resolves one service at a time and
 * drops the rest with FAILURE_ALREADY_ACTIVE.
 */
object Mdns {
    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    fun browse(service: String, timeoutMs: Int): List<MdnsService> {
        val replies = mutableListOf<Pair<ByteArray, InetAddress>>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val query = query(service)
            val group = InetAddress.getByName(GROUP)
            repeat(2) { socket.send(DatagramPacket(query, query.size, group, PORT)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                replies += packet.data.copyOf(packet.length) to packet.address
            }
        }
        return replies.flatMap { (bytes, from) -> runCatching { services(bytes, service, from) }.getOrDefault(emptyList()) }
            .distinctBy { it.instance }
    }

    fun query(service: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4)) // id 0, standard query
        out.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)) // one question
        writeName(out, service)
        out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0, 1))
        return out.toByteArray().also {
            // A random id, which a legacy unicast reply echoes.
            val id = Random.nextInt(1, 0xFFFF)
            it[0] = (id shr 8).toByte()
            it[1] = id.toByte()
        }
    }

    /** Every instance of [service] one reply describes, with what it says about each. */
    fun services(packet: ByteArray, service: String, from: InetAddress): List<MdnsService> {
        val reader = Reader(packet)
        reader.at = 4
        val questions = reader.u16()
        val records = reader.u16() + reader.u16() + reader.u16()
        reader.at = 12
        repeat(questions) {
            reader.name()
            reader.at += 4
        }

        val instances = linkedSetOf<String>()
        val srv = mutableMapOf<String, Pair<String, Int>>()
        val txt = mutableMapOf<String, Map<String, String>>()
        val addresses = mutableMapOf<String, String>()
        repeat(records) {
            val name = reader.name()
            val type = reader.u16()
            reader.at += 6 // class, ttl
            val length = reader.u16()
            val end = reader.at + length
            when (type) {
                TYPE_PTR -> if (name.equals(service, ignoreCase = true)) instances += reader.name()
                TYPE_SRV -> {
                    reader.at += 4 // priority, weight
                    val port = reader.u16()
                    srv[name.lowercase()] = reader.name() to port
                }
                TYPE_TXT -> txt[name.lowercase()] = reader.txt(end)
                TYPE_A -> if (length == 4) {
                    addresses[name.lowercase()] = packet.copyOfRange(reader.at, end)
                        .joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
            }
            reader.at = end
        }
        return instances.map { instance ->
            val (target, port) = srv[instance.lowercase()] ?: ("" to 0)
            MdnsService(
                instance = instance,
                address = addresses[target.lowercase()] ?: from.hostAddress.orEmpty(),
                port = port.takeIf { it > 0 } ?: 8009,
                txt = txt[instance.lowercase()].orEmpty()
            )
        }
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private class Reader(val bytes: ByteArray) {
        var at = 0

        fun u16(): Int = ((bytes[at].toInt() and 0xFF) shl 8 or (bytes[at + 1].toInt() and 0xFF)).also { at += 2 }

        /** A name, following compression pointers without moving past the first one. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = at
            var jumped = false
            var hops = 0
            while (true) {
                val length = bytes[cursor].toInt() and 0xFF
                when {
                    length == 0 -> {
                        if (!jumped) at = cursor + 1
                        return labels.joinToString(".")
                    }
                    length and 0xC0 == 0xC0 -> {
                        if (++hops > 32) error("bucle de punteros en un nombre mDNS")
                        val pointer = (length and 0x3F) shl 8 or (bytes[cursor + 1].toInt() and 0xFF)
                        if (!jumped) at = cursor + 2
                        jumped = true
                        cursor = pointer
                    }
                    else -> {
                        labels += String(bytes, cursor + 1, length, Charsets.UTF_8)
                        cursor += 1 + length
                    }
                }
            }
        }

        fun txt(end: Int): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            while (at < end) {
                val length = bytes[at].toInt() and 0xFF
                val entry = String(bytes, at + 1, length, Charsets.UTF_8)
                at += 1 + length
                if (entry.isNotEmpty()) entries[entry.substringBefore('=')] = entry.substringAfter('=', "")
            }
            return entries
        }
    }
}
