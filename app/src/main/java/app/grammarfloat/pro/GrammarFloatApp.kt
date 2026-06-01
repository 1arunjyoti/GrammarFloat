package app.grammarfloat.pro

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import app.grammarfloat.pro.storage.SettingsStore

class GrammarFloatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val settingsStore = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(settingsStore.getThemeMode())
    }
}
