package com.memora.app.data

import com.memora.app.local.OperatingMode
import kotlinx.coroutines.sync.Mutex

/** The path a request to the laptop actually took. Tailscale is the optional remote route; LAN is the default. */
enum class LaptopRoute(val label: String) { LAN("LAN"), TAILSCALE("Tailscale") }

/**
 * Where the laptop is on the user's tailnet. Tailscale itself runs as the official app on both devices; Memora only
 * needs the laptop's Tailscale address, because the phone's operating system routes it through the tailnet.
 */
data class TailscaleConfig(val enabled: Boolean = false, val host: String = "", val port: Int = DEFAULT_PORT) {
    val configured: Boolean get() = enabled && host.isNotBlank()

    companion object { const val DEFAULT_PORT = 8010 }
}

object TailscaleAddress {
    /** 100.64.0.0/10, the range Tailscale assigns to devices. */
    fun isTailscaleIpv4(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p.all { it in 0..255 } && p[0] == 100 && p[1] in 64..127
    }

    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    /**
     * Returns a message for the user, or null when [host] and [port] look usable. Accepts a Tailscale IPv4 address,
     * a MagicDNS name ending in .ts.net, or a short MagicDNS name. Anything that looks like a URL, a public address
     * or an ordinary internet host name is rejected, so the pairing code can never be sent to one.
     */
    fun validate(host: String, port: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return "Enter the laptop's Tailscale address, for example 100.101.102.103 or my-laptop.tailnet-name.ts.net."
        if (h.contains("://") || h.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) {
            return "Enter only the address, without http://, slashes or spaces."
        }
        if (h.contains(':')) return "Enter the address without a port; the port goes in its own field."
        val lower = h.lowercase()
        if (lower == "localhost" || lower.startsWith("127.") || lower == "0.0.0.0") {
            return "“$h” means this phone itself. Enter the laptop's Tailscale address."
        }
        val looksLikeIp = Regex("^[0-9.]+$").matches(h)
        if (looksLikeIp) {
            if (!isTailscaleIpv4(h)) return "“$h” is not a Tailscale address. Tailscale addresses start with 100.64 to 100.127."
        } else {
            val labels = lower.split('.')
            if (lower.length > 253 || labels.any { !LABEL.matches(it) }) return "“$h” is not a valid host name."
            if (labels.size > 1 && !lower.endsWith(".ts.net")) {
                return "“$h” is not a Tailscale name. Use the laptop's 100.x address or its MagicDNS name ending in .ts.net."
            }
        }
        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1..65535) return "The port must be a number between 1 and 65535 (Memora usually uses 8010)."
        return null
    }
}

data class RouteAttempt(val route: LaptopRoute, val error: MemoraException)

sealed interface RouteOutcome {
    data class Connected(val route: LaptopRoute, val config: ServerConfig, val info: ServerInfo, val skipped: List<RouteAttempt>) : RouteOutcome
    data class Failed(val attempts: List<RouteAttempt>) : RouteOutcome {
        val message: String get() = attempts.joinToString("\n") { "${it.route.label}: ${it.error.message}" }
        val kind: MemoraException.Kind? get() = attempts.lastOrNull()?.error?.kind
    }
    /** Phone-only mode, or nothing to connect to: no request was made. */
    data class NotAttempted(val reason: String) : RouteOutcome
    /** Another attempt is already running; this call did nothing. */
    data object Busy : RouteOutcome
}

/**
 * Chooses and verifies the route to the laptop. LAN is tried first, then Tailscale if it is configured.
 * Only "can't get there" failures (unreachable, timeout) move on to the next route: a wrong pairing code or a
 * server that isn't Memora is reported as it is, because trying another address would not fix it.
 * One attempt at a time, each bounded by the probe's own timeouts; nothing here retries on its own.
 */
class ConnectionRouter(private val probe: suspend (ServerConfig) -> ServerInfo) {
    private val gate = Mutex()

    suspend fun connect(mode: OperatingMode, lan: ServerConfig?, tailscale: TailscaleConfig, code: String): RouteOutcome {
        if (mode == OperatingMode.PHONE_ONLY) return RouteOutcome.NotAttempted("Phone only mode never contacts the laptop.")
        val targets = buildList {
            if (lan != null) add(LaptopRoute.LAN to lan)
            if (tailscale.configured && code.isNotBlank()) add(LaptopRoute.TAILSCALE to ServerConfig(tailscale.host.trim(), tailscale.port, code.trim()))
        }
        if (targets.isEmpty()) return RouteOutcome.NotAttempted("No laptop route is set up.")
        if (!gate.tryLock()) return RouteOutcome.Busy
        try {
            val attempts = mutableListOf<RouteAttempt>()
            for ((route, config) in targets) {
                try {
                    return RouteOutcome.Connected(route, config, probe(config), attempts.toList())
                } catch (e: MemoraException) {
                    attempts += RouteAttempt(route, e)
                    val canTryNext = e.kind == MemoraException.Kind.UNREACHABLE || e.kind == MemoraException.Kind.TIMEOUT
                    if (!canTryNext) break
                }
            }
            return RouteOutcome.Failed(attempts)
        } finally {
            gate.unlock()
        }
    }

    /** Tests exactly one route (the Settings "Test connection" button); never falls back. */
    suspend fun test(route: LaptopRoute, config: ServerConfig): RouteOutcome {
        if (!gate.tryLock()) return RouteOutcome.Busy
        try {
            return try {
                RouteOutcome.Connected(route, config, probe(config), emptyList())
            } catch (e: MemoraException) {
                RouteOutcome.Failed(listOf(RouteAttempt(route, e)))
            }
        } finally {
            gate.unlock()
        }
    }
}
