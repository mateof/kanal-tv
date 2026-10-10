package com.mateof.kanal.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputTest {

    @Test
    fun ratesComeFromTheTimeThatActuallyPassed() {
        val meter = Throughput()
        meter.add(0, 0)
        meter.add(1_000_000, 1_000)
        // A late tick: two seconds for two megabytes is still one a second.
        meter.add(3_000_000, 3_000)
        assertEquals(listOf(1_000_000L, 1_000_000L), meter.history)
    }

    @Test
    fun theCurrentRateAveragesTheLastFewSeconds() {
        val meter = Throughput()
        var total = 0L
        meter.add(total, 0)
        listOf(100L, 100L, 400L, 400L, 400L).forEachIndexed { i, rate ->
            total += rate
            meter.add(total, (i + 1) * 1_000L)
        }
        assertEquals(400L, meter.current(3))
        assertEquals(280L, meter.current(5))
    }

    @Test
    fun aCountThatStartsAgainIsANewBaselineNotANegativeRate() {
        val meter = Throughput()
        meter.add(0, 0)
        meter.add(5_000, 1_000)
        // A new relay counts from zero.
        meter.add(200, 2_000)
        meter.add(1_200, 3_000)
        assertEquals(listOf(5_000L, 1_000L), meter.history)
        assertTrue(meter.history.all { it >= 0 })
    }

    @Test
    fun keepsOnlyTheLastMinute() {
        val meter = Throughput(keep = 60)
        for (second in 0..90) meter.add(second * 10L, second * 1_000L)
        assertEquals(60, meter.history.size)
        assertEquals(0L, Throughput().current())
    }
}
