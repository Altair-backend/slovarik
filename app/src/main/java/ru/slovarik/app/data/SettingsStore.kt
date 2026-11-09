package ru.slovarik.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** 0 — как в системе, 1 — светлая, 2 — тёмная. */
    val themeMode: Int = THEME_SYSTEM,
    /** Material You: цвета из обоев (Android 12+). */
    val dynamicColor: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
) {
    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeKey = intPreferencesKey("theme_mode")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val reminderEnabledKey = booleanPreferencesKey("reminder_enabled")
    private val reminderHourKey = intPreferencesKey("reminder_hour")
    private val reminderMinuteKey = intPreferencesKey("reminder_minute")
    private val seededKey = booleanPreferencesKey("demo_seeded")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[themeKey] ?: AppSettings.THEME_SYSTEM,
            dynamicColor = prefs[dynamicKey] ?: true,
            reminderEnabled = prefs[reminderEnabledKey] ?: false,
            reminderHour = prefs[reminderHourKey] ?: 19,
            reminderMinute = prefs[reminderMinuteKey] ?: 0,
        )
    }

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[themeKey] = mode }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[dynamicKey] = enabled }
    }

    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit {
            it[reminderEnabledKey] = enabled
            it[reminderHourKey] = hour
            it[reminderMinuteKey] = minute
        }
    }

    suspend fun isSeeded(): Boolean = context.dataStore.data.first()[seededKey] ?: false

    suspend fun setSeeded() {
        context.dataStore.edit { it[seededKey] = true }
    }

    /** Лучшее время в режиме «Сопоставление» для набора, мс. */
    fun bestMatchTime(setId: Long): Flow<Long?> =
        context.dataStore.data.map { it[longPreferencesKey("best_match_$setId")] }

    suspend fun saveBestMatchTime(setId: Long, timeMs: Long) {
        val key = longPreferencesKey("best_match_$setId")
        context.dataStore.edit { prefs ->
            val current = prefs[key]
            if (current == null || timeMs < current) prefs[key] = timeMs
        }
    }
}
