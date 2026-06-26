package com.family.bankapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val reminderDaysKey = intPreferencesKey("default_reminder_days")
    private val forecastDaysKey = intPreferencesKey("forecast_days")

    val defaultReminderDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[reminderDaysKey] ?: 3
    }

    val forecastDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[forecastDaysKey] ?: 14
    }

    suspend fun setDefaultReminderDays(days: Int) {
        context.dataStore.edit { it[reminderDaysKey] = days.coerceIn(0, 14) }
    }

    suspend fun setForecastDays(days: Int) {
        context.dataStore.edit { it[forecastDaysKey] = days.coerceIn(7, 60) }
    }
}
