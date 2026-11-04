package com.memora.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun reducedMotionRemovesDurations() {
        assertEquals(0, Motion.ms(Motion.Emphasized, reduced = true))
        assertEquals(Motion.Standard, Motion.ms(Motion.Standard, reduced = false))
    }

    @Test fun durationCategoriesAreOrderedAndInRange() {
        assertTrue(Motion.Micro in 100..180)
        assertTrue(Motion.Standard in 180..320)
        assertTrue(Motion.Emphasized in 300..500)
        assertTrue(Motion.Micro < Motion.Standard && Motion.Standard < Motion.Emphasized)
    }

    @Test fun themeChoicesCoverSystemLightDark() {
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeChoice.entries.map { it.name })
    }
}
