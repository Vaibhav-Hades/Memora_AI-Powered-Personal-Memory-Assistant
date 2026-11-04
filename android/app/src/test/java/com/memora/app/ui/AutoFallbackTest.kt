package com.memora.app.ui

import com.memora.app.data.MemoraException
import com.memora.app.data.MemoraException.Kind
import com.memora.app.local.OperatingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFallbackTest {
    private fun e(k: Kind) = MemoraException("x", k)

    @Test fun automaticFallsBackWhenLaptopVanishes() {
        assertTrue(AutoFallback.shouldFallBack(OperatingMode.AUTO, e(Kind.UNREACHABLE)))
        assertTrue(AutoFallback.shouldFallBack(OperatingMode.AUTO, e(Kind.TIMEOUT)))
    }

    @Test fun laptopModeNeverFallsBackSilently() {
        assertFalse(AutoFallback.shouldFallBack(OperatingMode.LAPTOP, e(Kind.UNREACHABLE)))
        assertFalse(AutoFallback.shouldFallBack(OperatingMode.PHONE_ONLY, e(Kind.TIMEOUT)))
    }

    @Test fun laptopsOwnErrorsAreNotTreatedAsUnavailable() {
        listOf(Kind.UNAUTHORIZED, Kind.AI_UNAVAILABLE, Kind.REJECTED, Kind.SERVER, Kind.NOT_MEMORA).forEach {
            assertFalse(AutoFallback.shouldFallBack(OperatingMode.AUTO, e(it)))
        }
        assertFalse(AutoFallback.shouldFallBack(OperatingMode.AUTO, IllegalStateException("x")))
    }
}
