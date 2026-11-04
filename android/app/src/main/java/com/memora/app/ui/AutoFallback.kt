package com.memora.app.ui

import com.memora.app.data.MemoraException
import com.memora.app.local.OperatingMode

/**
 * In Automatic mode, a question the laptop could not take (it vanished after the app last saw it) is answered on the
 * phone instead, and the answer says so. Laptop mode never falls back, and a laptop that answered with an error of its own
 * (wrong code, rejected request, server fault) is not treated as "unavailable".
 */
object AutoFallback {
    fun shouldFallBack(mode: OperatingMode, e: Exception): Boolean =
        mode == OperatingMode.AUTO && e is MemoraException &&
            e.kind in setOf(MemoraException.Kind.UNREACHABLE, MemoraException.Kind.TIMEOUT)
}
