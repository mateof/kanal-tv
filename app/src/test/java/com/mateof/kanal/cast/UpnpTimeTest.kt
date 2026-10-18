package com.mateof.kanal.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpnpTimeTest {

    @Test
    fun readsTheClockRenderersWrite() {
        assertEquals(3_723_000L, upnpTimeToMs("1:02:03"))
        assertEquals(3_723_500L, upnpTimeToMs("01:02:03.500"))
        assertNull(upnpTimeToMs("NOT_IMPLEMENTED"))
    }

    @Test
    fun writesWholeSecondsWithUnpaddedHours() {
        assertEquals("1:02:03", msToUpnpTime(3_723_999))
        assertEquals("0:00:00", msToUpnpTime(-5))
    }
}
