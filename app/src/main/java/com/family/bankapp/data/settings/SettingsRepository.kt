package com.family.bankapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val reminderDaysKey = intPreferencesKey("default_reminder_days")
    private val forecastDaysKey = intPreferencesKey("forecast_days")
    private val plaidServerUrlKey = stringPreferencesKey("plaid_server_url")
    private val plaidItemLimitKey = intPreferencesKey("plaid_item_limit")
    private val appActivatedKey = booleanPreferencesKey("app_activated")

    val defaultReminderDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[reminderDaysKey] ?: 3
    }

    val forecastDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[forecastDaysKey] ?: 14
    }

    /** Optional fallback until Plaid moves to Supabase Edge Functions. */
    val plaidServerUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[plaidServerUrlKey] ?: ""
    }

    /** Plaid Trial default is 10 items. */
    val plaidItemLimit: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[plaidItemLimitKey] ?: 10
    }

    val isAppActivated: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[appActivatedKey] ?: false
    }

    suspend fun setDefaultReminderDays(days: Int) {
        context.dataStore.edit { it[reminderDaysKey] = days.coerceIn(0, 14) }
    }

    suspend fun setForecastDays(days: Int) {
        context.dataStore.edit { it[forecastDaysKey] = days.coerceIn(7, 60) }
    }

    suspend fun setPlaidServerUrl(url: String) {
        context.dataStore.edit { it[plaidServerUrlKey] = url.trim().removeSuffix("/") }
    }

    suspend fun setPlaidItemLimit(limit: Int) {
        context.dataStore.edit { it[plaidItemLimitKey] = limit.coerceIn(1, 100) }
    }

    suspend fun setAppActivated(activated: Boolean) {
        context.dataStore.edit { it[appActivatedKey] = activated }
    }
}
