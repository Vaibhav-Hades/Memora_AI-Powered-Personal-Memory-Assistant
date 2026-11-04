package com.memora.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit

enum class ThemeChoice(val label: String) { SYSTEM("Follow phone"), LIGHT("Light"), DARK("Dark") }

/** How the app looks and moves. Stored on the phone only; nothing here is ever sent anywhere. */
class UiSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("memora-ui", Context.MODE_PRIVATE)

    var theme by mutableStateOf(runCatching { ThemeChoice.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ThemeChoice.SYSTEM))
        private set
    var reduceMotion by mutableStateOf(prefs.getBoolean("reduce_motion", false))
        private set
    var introSeen by mutableStateOf(prefs.getBoolean("intro_seen", false))
        private set

    fun chooseTheme(value: ThemeChoice) { theme = value; prefs.edit { putString("theme", value.name) } }
    fun chooseReduceMotion(value: Boolean) { reduceMotion = value; prefs.edit { putBoolean("reduce_motion", value) } }
    fun markIntroSeen(value: Boolean = true) { introSeen = value; prefs.edit { putBoolean("intro_seen", value) } }

    /** True when Android's own "remove animations" accessibility setting (animator scale 0) is on. */
    fun systemReducesMotion(): Boolean =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}

val LocalUiSettings = staticCompositionLocalOf<UiSettings?> { null }

/** When true, motion is replaced by simple fades or immediate changes. */
val LocalReducedMotion = staticCompositionLocalOf { false }
