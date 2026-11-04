package com.memora.app.ui

import com.memora.app.local.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the Settings buttons that "do nothing": "Test & save connection" is disabled while the connection
 * state is Checking. In Phone only mode with a saved laptop, the state started as Checking and no check ever ran to
 * end it, so the button stayed disabled for good.
 */
class InitialConnectionTest {
    @Test fun phoneOnlyWithSavedLaptopDoesNotStartInChecking() {
        assertEquals(Connection.NotConfigured, initialConnection(hasLaptopRoute = true, mode = OperatingMode.PHONE_ONLY))
    }

    @Test fun modesThatCheckAtStartupStillStartInChecking() {
        assertEquals(Connection.Checking, initialConnection(true, OperatingMode.AUTO))
        assertEquals(Connection.Checking, initialConnection(true, OperatingMode.LAPTOP))
    }

    @Test fun noLaptopConfiguredIsNeverChecking() {
        OperatingMode.entries.forEach { assertEquals(Connection.NotConfigured, initialConnection(false, it)) }
    }

    @Test fun everyStartingStateLeavesTheTestButtonUsable() {
        // The Settings button is enabled exactly when the state is not Checking, i.e. when no check is actually running.
        // Only the modes that really run a check at start-up may begin in Checking.
        OperatingMode.entries.forEach { mode ->
            val state = initialConnection(true, mode)
            assertTrue(mode.name, state !is Connection.Checking || mode != OperatingMode.PHONE_ONLY)
        }
    }
}
