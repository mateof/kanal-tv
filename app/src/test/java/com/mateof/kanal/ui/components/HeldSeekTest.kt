package com.mateof.kanal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldSeekTest {

    @Test
    fun aPressIsTenSeconds() {
        val seek = HeldSeek()
        assertEquals(10_000L, seek.step(repeat = 0, now = 0))
        assertEquals(10_000L, seek.step(repeat = 0, now = 300))
    }

    @Test
    fun holdingMovesByTimeHeldNotByRepeats() {
        // The same second held, once in repeats of 50 ms and once of 100 ms.
        fun held(every: Long): Long {
            val seek = HeldSeek()
            var total = seek.step(0, 0)
            var t = 0L
            var repeat = 0
            while (t < 1_000) {
                t += every
                total += seek.step(++repeat, t)
            }
            return total
        }
        assertEquals(held(50), held(100))
    }

    @Test
    fun aLongHoldCrossesAFilmButNotInstantly() {
        val seek = HeldSeek()
        var total = seek.step(0, 0)
        var t = 0L
        var repeat = 0
        while (t < 1_000) {
            t += 50
            total += seek.step(++repeat, t)
        }
        assertTrue("one second held: $total ms", total in 30_000L..60_000L)
        while (t < 15_000) {
            t += 50
            total += seek.step(++repeat, t)
        }
        assertTrue("fifteen seconds held: $total ms", total > 60 * 60_000L)
    }
}
