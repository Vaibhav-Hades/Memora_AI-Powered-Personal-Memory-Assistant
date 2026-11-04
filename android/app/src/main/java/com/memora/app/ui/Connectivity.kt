package com.memora.app.ui

import com.memora.app.data.LaptopRoute
import com.memora.app.local.OperatingMode

enum class Tone { OK, CAUTION, ERROR, NEUTRAL }

/** One line describing where Memora is answering from right now. Built only from verified state. */
data class ConnectivityStatus(val label: String, val detail: String, val tone: Tone)

object Connectivity {
    /**
     * [connection] is the last verified result: "via Tailscale" appears only after a real request went through that
     * route, never merely because an address is configured.
     */
    fun describe(mode: OperatingMode, connection: Connection, hasLaptopRoute: Boolean): ConnectivityStatus = when (mode) {
        OperatingMode.PHONE_ONLY -> ConnectivityStatus("Phone only", "Everything stays on this phone.", Tone.OK)
        else -> when {
            !hasLaptopRoute -> if (mode == OperatingMode.LAPTOP) ConnectivityStatus("Laptop not set up", "Add the laptop in Settings.", Tone.CAUTION)
            else ConnectivityStatus("On this phone", "No laptop is set up, so Memora uses this phone.", Tone.NEUTRAL)
            connection is Connection.Checking -> ConnectivityStatus("Connecting…", "Looking for the laptop.", Tone.NEUTRAL)
            connection is Connection.Connected -> ConnectivityStatus(
                if (connection.route == LaptopRoute.TAILSCALE) "Laptop via Tailscale" else "Laptop via LAN",
                if (connection.route == LaptopRoute.TAILSCALE) "Connected through your private Tailscale network." else "Connected on the same Wi-Fi.",
                Tone.OK,
            )
            connection is Connection.Failed && connection.unauthorized ->
                ConnectivityStatus("Pairing code rejected", "The laptop answered, but did not accept the code.", Tone.ERROR)
            mode == OperatingMode.LAPTOP -> ConnectivityStatus("Laptop unavailable", "Laptop mode does not fall back to the phone.", Tone.ERROR)
            else -> ConnectivityStatus("Laptop unavailable", "Using this phone's files and AI instead.", Tone.CAUTION)
        }
    }
}
