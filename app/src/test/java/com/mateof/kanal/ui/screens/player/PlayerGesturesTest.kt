package com.mateof.kanal.ui.screens.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerGesturesTest {

    @Test
    fun threeFingersUpIsACast() {
        assertEquals(PlayerGesture.Cast, PlayerGestures.pending(Offset(10f, -200f), true, true, fingers = 3))
    }

    @Test
    fun threeFingersAnyOtherWayIsNothing() {
        assertNull(PlayerGestures.pending(Offset(0f, 200f), true, true, fingers = 3))
        assertNull(PlayerGestures.pending(Offset(-200f, -10f), true, true, fingers = 3))
    }

    @Test
    fun twoFingersAreNothing() {
        assertNull(PlayerGestures.pending(Offset(0f, -200f), true, true, fingers = 2))
    }

    @Test
    fun oneFingerKeepsItsOldMeanings() {
        assertEquals(PlayerGesture.Fullscreen, PlayerGestures.pending(Offset(0f, -200f), true, true))
        assertEquals(PlayerGesture.Pip, PlayerGestures.pending(Offset(0f, 200f), true, true))
        assertEquals(PlayerGesture.Back, PlayerGestures.pending(Offset(200f, 0f), true, true))
    }

    @Test
    fun zonesAreThirds() {
        assertEquals(TouchZone.Left, PlayerGestures.zoneOf(100f, 900))
        assertEquals(TouchZone.Centre, PlayerGestures.zoneOf(450f, 900))
        assertEquals(TouchZone.Right, PlayerGestures.zoneOf(800f, 900))
    }

    @Test
    fun dialRunsEndToEndOverMostOfTheHeight() {
        // 0.6 of 1000 px is the whole range.
        assertEquals(1f, PlayerGestures.level(0f, -600f, 1000), 0.001f)
        assertEquals(0.75f, PlayerGestures.level(0.5f, -150f, 1000), 0.001f)
        assertEquals(0f, PlayerGestures.level(0.2f, 900f, 1000), 0.001f)
    }

    @Test
    fun goingBackGivesTheStartingLevel() {
        assertEquals(0.4f, PlayerGestures.level(0.4f, 0f, 1000), 0.001f)
    }
}
