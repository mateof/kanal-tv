package com.mateof.kanal.cast.relay

import com.mateof.kanal.data.net.redactUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTokensTest {

    @Test
    fun issues128RandomBitsInHex() {
        val token = RelayTokens.issue()
        assertEquals(32, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun everySessionGetsADifferentToken() {
        val tokens = List(500) { RelayTokens.issue() }.toSet()
        assertEquals(500, tokens.size)
    }

    @Test
    fun readsTheTokenWithOrWithoutAnExtension() {
        val token = RelayTokens.issue()
        assertEquals(token, RelayTokens.fromPath("/cast/$token"))
        assertEquals(token, RelayTokens.fromPath("/cast/$token.ts"))
        assertEquals(token, RelayTokens.fromPath("/cast/$token.ts?foo=bar"))
        assertEquals("/cast/$token.ts", RelayTokens.path(token, ".ts"))
    }

    @Test
    fun rejectsPathsThatAreNotOurs() {
        val token = RelayTokens.issue()
        assertNull(RelayTokens.fromPath("/"))
        assertNull(RelayTokens.fromPath("/cast/"))
        assertNull(RelayTokens.fromPath("/cast/abc"))
        assertNull(RelayTokens.fromPath("/cast/${token.uppercase()}"))
        assertNull(RelayTokens.fromPath("/cast/${token}0"))
        assertNull(RelayTokens.fromPath("/other/$token"))
        assertNull(RelayTokens.fromPath("/cast/../$token"))
    }

    @Test
    fun matchesOnlyTheExactToken() {
        val token = RelayTokens.issue()
        assertTrue(RelayTokens.matches(token, token))
        assertFalse(RelayTokens.matches(token, RelayTokens.issue()))
        assertFalse(RelayTokens.matches(token, token.dropLast(1)))
        assertFalse(RelayTokens.matches(token, null))
    }

    @Test
    fun logsKeepOnlyTheStartOfTheToken() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(
            "http://192.168.1.50:41234/cast/0123***.ts",
            redactUrl("http://192.168.1.50:41234/cast/$token.ts")
        )
    }
}
