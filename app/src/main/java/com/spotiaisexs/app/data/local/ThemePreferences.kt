package com.spotiaisexs.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AccentMode(val storageValue: String) {
    MANUAL("manual"),
    DYNAMIC("dynamic"),
    MONOCHROME("monochrome");

    companion object {
        fun fromStorage(value: String?): AccentMode =
            entries.firstOrNull { it.storageValue == value } ?: MANUAL
    }
}

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/**
 * Defaults follow the SpotiAisexs redesign: dark-first (Spotify), pure black
 * for OLED panels, the tangerine accent and glass navigation chrome (Apple's
 * Liquid Glass layer). Every one is still a user setting.
 */
data class ThemePrefs(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentColor: String = DEFAULT_ACCENT,
    val accentLight: String = DEFAULT_ACCENT_LIGHT,
    val accentMode: AccentMode = AccentMode.MANUAL,
    val amoled: Boolean = true,
    val liquidGlass: Boolean = true,
) {
    companion object {
        const val DEFAULT_ACCENT = "#FF7A45"
        const val DEFAULT_ACCENT_LIGHT = "#E85F28"
    }
}

@Singleton
class ThemePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("lw_themeMode")
        val ACCENT_COLOR = stringPreferencesKey("lw_accent")
        val ACCENT_LIGHT = stringPreferencesKey("lw_accentLight")
        val ACCENT_MODE = stringPreferencesKey("lw_accentMode")
        val AMOLED = booleanPreferencesKey("lw_amoled")
        val LIQUID_GLASS = booleanPreferencesKey("lw_liquidGlass")
    }

    val prefs: Flow<ThemePrefs> = dataStore.data
        .recoverPreferences("ThemePreferences")
        .map { p ->
            ThemePrefs(
                themeMode = p.readSafely(Keys.THEME_MODE)?.let(ThemeMode::fromStorage) ?: ThemeMode.DARK,
                accentColor = p.readSafely(Keys.ACCENT_COLOR) ?: ThemePrefs.DEFAULT_ACCENT,
                accentLight = p.readSafely(Keys.ACCENT_LIGHT) ?: ThemePrefs.DEFAULT_ACCENT_LIGHT,
                accentMode = AccentMode.fromStorage(p.readSafely(Keys.ACCENT_MODE)),
                amoled = p.readSafely(Keys.AMOLED) ?: true,
                liquidGlass = p.readSafely(Keys.LIQUID_GLASS) ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageValue }
    }

    suspend fun setManualAccent(color: String, light: String) {
        dataStore.edit {
            it[Keys.ACCENT_COLOR] = color
            it[Keys.ACCENT_LIGHT] = light
            it[Keys.ACCENT_MODE] = AccentMode.MANUAL.storageValue
        }
    }

    suspend fun setMode(mode: AccentMode) {
        dataStore.edit { it[Keys.ACCENT_MODE] = mode.storageValue }
    }

    suspend fun setAmoled(enabled: Boolean) {
        dataStore.edit { it[Keys.AMOLED] = enabled }
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        dataStore.edit { it[Keys.LIQUID_GLASS] = enabled }
    }
}
