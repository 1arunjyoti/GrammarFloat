package app.grammarfloat.pro.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_OVERLAY_FONT_SIZE = "overlay_font_size"
        const val DEFAULT_OVERLAY_FONT_SIZE = 16f
        private const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_THEME_MODE = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun getOverlayFontSize(): Float {
        return prefs.getFloat(KEY_OVERLAY_FONT_SIZE, DEFAULT_OVERLAY_FONT_SIZE)
    }

    fun setOverlayFontSize(size: Float) {
        prefs.edit { putFloat(KEY_OVERLAY_FONT_SIZE, size) }
    }

    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE)
    }

    fun setThemeMode(mode: Int) {
        prefs.edit { putInt(KEY_THEME_MODE, mode) }
    }
}
