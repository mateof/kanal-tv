package com.mateof.kanal.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

class CastRoutingTest {

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    // --- Subnet ----------------------------------------------------------------

    @Test
    fun subnetContainsAddressesInsideItsPrefix() {
        val lan = Subnet.parse("192.168.1.0/24")
        assertTrue(lan.contains(ip("192.168.1.137")))
        assertTrue(lan.contains(ip("192.168.1.0")))
        assertFalse(lan.contains(ip("192.168.0.22")))
        assertFalse(lan.contains(ip("10.0.0.1")))
    }

    @Test
    fun subnetHandlesPrefixesThatAreNotWholeBytes() {
        val cgnat = Subnet.parse("100.64.0.0/10")
        assertTrue(cgnat.contains(ip("100.100.1.1")))
        assertTrue(cgnat.contains(ip("100.127.255.255")))
        assertFalse(cgnat.contains(ip("100.128.0.1")))
        assertFalse(cgnat.contains(ip("100.63.255.255")))
    }

    @Test
    fun defaultRouteContainsEverythingOfItsFamily() {
        val any = Subnet.parse("0.0.0.0/0")
        assertTrue(any.contains(ip("8.8.8.8")))
        assertTrue(any.contains(ip("192.168.0.22")))
        assertFalse(any.contains(ip("::1")))
    }

    @Test
    fun networkClearsTheHostBits() {
        assertEquals("192.168.1.0/24", Subnet(ip("192.168.1.37"), 24).network().toString())
        assertEquals("172.16.0.0/12", Subnet(ip("172.20.3.4"), 12).network().toString())
        assertEquals("10.0.0.0/8", Subnet(ip("10.9.8.7"), 8).network().toString())
    }

    // --- Private addresses -----------------------------------------------------

    @Test
    fun recognisesAddressesATelevisionElsewhereCannotReach() {
        listOf("192.168.0.22", "10.1.2.3", "172.16.5.5", "169.254.1.1", "127.0.0.1", "100.101.102.103", "fd00::1")
            .forEach { assertTrue(it, CastRouting.isPrivate(ip(it))) }
        listOf("8.8.8.8", "185.1.2.3", "172.32.0.1", "2a00:1450::1")
            .forEach { assertFalse(it, CastRouting.isPrivate(ip(it))) }
    }

    // --- VPN -------------------------------------------------------------------

    private val wifi = listOf(
        NetRoute(Subnet.parse("192.168.1.0/24"), vpn = false),
        NetRoute(Subnet.parse("0.0.0.0/0"), vpn = false)
    )

    @Test
    fun splitTunnelTakesOnlyItsOwnRange() {
        val routes = wifi + NetRoute(Subnet.parse("192.168.0.0/24"), vpn = true)
        assertTrue(CastRouting.viaVpn(routes, ip("192.168.0.22")))
        assertFalse(CastRouting.viaVpn(routes, ip("192.168.1.137")))
        assertFalse(CastRouting.viaVpn(routes, ip("8.8.8.8")))
    }

    @Test
    fun fullTunnelTakesEverythingEvenAgainstAMoreSpecificWifiRoute() {
        val routes = wifi + NetRoute(Subnet.parse("0.0.0.0/0"), vpn = true)
        assertTrue(CastRouting.viaVpn(routes, ip("192.168.0.22")))
        assertTrue(CastRouting.viaVpn(routes, ip("192.168.1.137")))
    }

    @Test
    fun noVpnNoVpnRoute() {
        assertFalse(CastRouting.viaVpn(wifi, ip("192.168.0.22")))
    }

    // --- Decision --------------------------------------------------------------

    private val tvLan = Subnet.parse("192.168.1.0/24")

    @Test
    fun theReportedCaseGoesThroughThePhone() {
        // Dispatcharr at home behind WireGuard, the LG on the other house's LAN.
        val decision = CastRouting.decide(ip("192.168.0.22"), tvLan, hostViaVpn = true)
        assertEquals(CastRoute.RELAY, decision.route)
    }

    @Test
    fun aPrivateHostOnAnotherSegmentGoesThroughThePhoneEvenWithoutAVpn() {
        val decision = CastRouting.decide(ip("10.8.0.1"), tvLan, hostViaVpn = false)
        assertEquals(CastRoute.RELAY, decision.route)
    }

    @Test
    fun aHostOnTheTelevisionsOwnSegmentGoesDirect() {
        // Even if a VPN claims the range: the television is right next to it.
        assertEquals(CastRoute.DIRECT, CastRouting.decide(ip("192.168.1.10"), tvLan, hostViaVpn = false).route)
        assertEquals(CastRoute.DIRECT, CastRouting.decide(ip("192.168.1.10"), tvLan, hostViaVpn = true).route)
    }

    @Test
    fun aPublicProviderGoesDirect() {
        assertEquals(CastRoute.DIRECT, CastRouting.decide(ip("185.1.2.3"), tvLan, hostViaVpn = false).route)
    }

    @Test
    fun aPublicProviderReachedThroughAVpnGoesThroughThePhone() {
        assertEquals(CastRoute.RELAY, CastRouting.decide(ip("185.1.2.3"), tvLan, hostViaVpn = true).route)
    }

    @Test
    fun withoutKnowingTheTelevisionsSegmentAPrivateHostStillAsksForTheRelay() {
        assertEquals(CastRoute.RELAY, CastRouting.decide(ip("192.168.0.22"), null, hostViaVpn = false).route)
        assertEquals(CastRoute.DIRECT, CastRouting.decide(ip("185.1.2.3"), null, hostViaVpn = false).route)
    }

    // --- Fallback --------------------------------------------------------------

    private fun refusal(action: String, http: Int, upnp: Int?) = UpnpException(action, http, upnp, null)

    @Test
    fun fallsBackWhenTheTelevisionCannotFetchTheAddress() {
        assertTrue(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 500, 716)))
        assertTrue(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 500, 714)))
        assertTrue(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 500, null)))
        assertTrue(CastRouting.shouldFallBack(refusal("Play", 500, 716)))
    }

    @Test
    fun fallsBackOnATimeoutWhereverItHappens() {
        assertTrue(CastRouting.shouldFallBack(SocketTimeoutException("timeout")))
        assertTrue(CastRouting.shouldFallBack(IOException("wrapped", SocketTimeoutException("timeout"))))
    }

    @Test
    fun doesNotFallBackOnRefusalsTheRelayCannotFix() {
        assertFalse(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 500, 701)))
        assertFalse(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 500, 402)))
        assertFalse(CastRouting.shouldFallBack(refusal("SetAVTransportURI", 404, null)))
        assertFalse(CastRouting.shouldFallBack(refusal("Stop", 500, 716)))
        assertFalse(CastRouting.shouldFallBack(IOException("connection refused")))
        assertFalse(CastRouting.shouldFallBack(IllegalStateException("anything")))
    }

    @Test
    fun upnpExceptionKeepsTheMessageTheSheetsHaveAlwaysShown() {
        val error = UpnpException("SetAVTransportURI", 500, 716, "Resource not found")
        assertEquals("SetAVTransportURI devolvió 500 · UPnP 716 · Resource not found", error.message)
        assertEquals("Play devolvió 500", UpnpException("Play", 500, null, null).message)
    }

    @Test
    fun suggestsTheStreamHostsSlash24AsTheTunnel() {
        assertEquals("192.168.0.0/24", CastRouting.suggestedTunnel(ip("192.168.0.22")))
        assertEquals("192.168.0.0/24", CastRouting.suggestedTunnel(null))
    }

    // --- Memory ----------------------------------------------------------------

    @Test
    fun remembersTheRoutePerTelevisionAndHost() {
        val memory = RouteMemory()
        val lg = RouteMemory.Key("uuid:lg", "192.168.0.22")
        val other = RouteMemory.Key("uuid:samsung", "192.168.0.22")
        assertNull(memory[lg])
        memory[lg] = CastRoute.RELAY
        assertEquals(CastRoute.RELAY, memory[lg])
        assertNull(memory[other])
        memory[lg] = CastRoute.DIRECT
        assertEquals(CastRoute.DIRECT, memory[lg])
        memory.forget(lg)
        assertNull(memory[lg])
    }

    // --- DLNA ------------------------------------------------------------------

    @Test
    fun directRouteAnnouncesExactlyWhatItAlwaysDid() {
        val source = CastSource.direct("http://host/live/u/p/1.m3u8")
        assertEquals("application/x-mpegURL", source.mime)
        assertEquals("DLNA.ORG_OP=00;DLNA.ORG_FLAGS=01700000000000000000000000000000", source.features)
        assertEquals("video/mp2t", CastSource.direct("http://host/live/u/p/1.ts?x=1").mime)
        assertEquals("video/mpeg", CastSource.direct("http://host/u/p/1").mime)
    }

    @Test
    fun protocolInfoCarriesTheMimeTheRelayServes() {
        assertEquals(
            "http-get:*:video/mp2t:${Dlna.LIVE_FEATURES}",
            Dlna.protocolInfo(Dlna.MIME_TS, Dlna.LIVE_FEATURES)
        )
        assertEquals(".ts", Dlna.extensionOf(Dlna.MIME_TS))
        assertEquals("", Dlna.extensionOf("video/mpeg"))
    }
}
