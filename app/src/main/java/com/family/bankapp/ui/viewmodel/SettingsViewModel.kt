package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = (application as BankAppApplication).settingsRepository

    val defaultReminderDays = settings.defaultReminderDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val forecastDays = settings.forecastDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14)

    fun setReminderDays(days: Int) {
        viewModelScope.launch { settings.setDefaultReminderDays(days) }
    }

    fun setForecastDays(days: Int) {
        viewModelScope.launch { settings.setForecastDays(days) }
    }
}
