package com.mateof.kanal.cast

import com.mateof.kanal.cast.gcast.CastMessage
import com.mateof.kanal.cast.gcast.GoogleCastClient
import com.mateof.kanal.cast.gcast.Mdns
import com.mateof.kanal.cast.webos.WebOsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class GoogleCastWireTest {

    @Test
    fun `a cast message survives encoding`() {
        val message = CastMessage(
            "sender-0",
            "receiver-0",
            "urn:x-cast:com.google.cast.receiver",
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1,"title":"Ñandú · 1080p"}"""
        )
        assertEquals(message, CastMessage.decode(message.encode()))
    }

    @Test
    fun `the encoding is the one the protocol defines`() {
        // protocol_version=0, source "a", destination "b", namespace "c", STRING, payload "d"
        val expected = byteArrayOf(
            0x08, 0x00,
            0x12, 0x01, 'a'.code.toByte(),
            0x1a, 0x01, 'b'.code.toByte(),
            0x22, 0x01, 'c'.code.toByte(),
            0x28, 0x00,
            0x32, 0x01, 'd'.code.toByte()
        )
        assertTrue(expected.contentEquals(CastMessage("a", "b", "c", "d").encode()))
    }

    @Test
    fun `a payload longer than 127 bytes gets a two-byte length`() {
        val payload = "x".repeat(300)
        assertEquals(payload, CastMessage.decode(CastMessage("s", "d", "n", payload).encode()).payload)
    }

    @Test
    fun `an mdns reply yields the chromecast with its name and address`() {
        val packet = DnsPacket().apply {
            header(answers = 1, additional = 3)
            name("_googlecast._tcp.local"); u16(12); u16(1); u32(120)
            rdata { name("Chromecast-abc._googlecast._tcp.local") }
            name("Chromecast-abc._googlecast._tcp.local"); u16(33); u16(0x8001); u32(120)
            rdata { u16(0); u16(0); u16(8009); name("abc.local") }
            name("Chromecast-abc._googlecast._tcp.local"); u16(16); u16(0x8001); u32(4500)
            rdata { txt("id=abc123"); txt("fn=Salón"); txt("md=Chromecast") }
            name("abc.local"); u16(1); u16(0x8001); u32(120)
            rdata { bytes(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)) }
        }.build()

        val found = Mdns.services(packet, "_googlecast._tcp.local", InetAddress.getByName("192.168.1.99"))

        assertEquals(1, found.size)
        assertEquals("192.168.1.50", found[0].address)
        assertEquals(8009, found[0].port)
        assertEquals("Salón", found[0].txt["fn"])
        assertEquals("abc123", found[0].txt["id"])
    }

    @Test
    fun `compressed names are followed`() {
        val packet = DnsPacket().apply {
            header(answers = 1, additional = 0)
            val service = position()
            name("_googlecast._tcp.local"); u16(12); u16(1); u32(120)
            // "TV" + pointer to the service name
            rdata { u8(2); bytes("TV".toByteArray()); pointer(service) }
        }.build()

        val found = Mdns.services(packet, "_googlecast._tcp.local", InetAddress.getByName("10.0.0.7"))

        assertEquals("TV._googlecast._tcp.local", found.single().instance)
        // No A record: the address the reply came from.
        assertEquals("10.0.0.7", found.single().address)
    }

    @Test
    fun `player states map to what the monitor understands`() {
        assertEquals("PLAYING", GoogleCastClient.upnpState("PLAYING"))
        assertEquals("PAUSED_PLAYBACK", GoogleCastClient.upnpState("PAUSED"))
        assertEquals("TRANSITIONING", GoogleCastClient.upnpState("BUFFERING"))
        assertEquals("STOPPED", GoogleCastClient.upnpState("IDLE"))
        assertEquals("PLAYING", WebOsClient.upnpState("playing"))
        assertEquals("PAUSED_PLAYBACK", WebOsClient.upnpState("paused"))
        assertEquals("STOPPED", WebOsClient.upnpState("unloaded"))
        assertEquals("TRANSITIONING", WebOsClient.upnpState("loaded"))
    }

    /** Writes a DNS response by hand, the way a responder would. */
    private class DnsPacket {
        private val out = ByteArrayOutputStream()

        fun position(): Int = out.size()
        fun u8(v: Int) = out.write(v)
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun bytes(b: ByteArray) = out.write(b)
        fun pointer(at: Int) = u16(0xC000 or at)
        fun txt(entry: String) { val b = entry.toByteArray(); u8(b.size); bytes(b) }

        fun name(value: String) {
            value.split('.').forEach { label -> val b = label.toByteArray(); u8(b.size); bytes(b) }
            u8(0)
        }

        fun header(answers: Int, additional: Int) {
            u16(0); u16(0x8400); u16(0); u16(answers); u16(0); u16(additional)
        }

        fun rdata(block: DnsPacket.() -> Unit) {
            // Written in place, since pointers inside the record are absolute.
            val lengthAt = out.size()
            u16(0)
            val start = out.size()
            block()
            val length = out.size() - start
            val bytes = out.toByteArray()
            bytes[lengthAt] = (length shr 8).toByte()
            bytes[lengthAt + 1] = length.toByte()
            out.reset()
            out.write(bytes)
        }

        fun build(): ByteArray = out.toByteArray()
    }
}
