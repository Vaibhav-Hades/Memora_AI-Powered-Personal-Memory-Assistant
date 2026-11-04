package com.memora.app.data

import android.content.Context
import androidx.core.content.edit

/** Remembers the laptop's address, port and pairing code on this phone, plus the optional Tailscale route. */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("memora", Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val host = prefs.getString("host", null) ?: return null
        return ServerConfig(host, prefs.getInt("port", DEFAULT_PORT), prefs.getString("code", "") ?: "")
    }

    fun save(config: ServerConfig) {
        prefs.edit {
            putString("host", config.host)
            putInt("port", config.port)
            putString("code", config.code)
        }
    }

    /** The pairing code is shared by both routes: it identifies the laptop, not the path to it. */
    fun code(): String = prefs.getString("code", "") ?: ""

    fun saveCode(code: String) = prefs.edit { putString("code", code) }

    fun loadTailscale(): TailscaleConfig = TailscaleConfig(
        enabled = prefs.getBoolean("ts_enabled", false),
        host = prefs.getString("ts_host", "") ?: "",
        port = prefs.getInt("ts_port", TailscaleConfig.DEFAULT_PORT),
    )

    fun saveTailscale(config: TailscaleConfig) = prefs.edit {
        putBoolean("ts_enabled", config.enabled)
        putString("ts_host", config.host)
        putInt("ts_port", config.port)
    }

    /** When a request last succeeded and through which route; shown as history, never used as proof of reachability. */
    fun lastSuccess(): Pair<LaptopRoute, Long>? {
        val at = prefs.getLong("last_ok_at", 0L).takeIf { it > 0 } ?: return null
        val route = LaptopRoute.entries.firstOrNull { it.name == prefs.getString("last_ok_route", null) } ?: return null
        return route to at
    }

    fun recordSuccess(route: LaptopRoute, atMillis: Long) = prefs.edit {
        putString("last_ok_route", route.name)
        putLong("last_ok_at", atMillis)
    }

    companion object {
        const val DEFAULT_PORT = 8000
    }
}
