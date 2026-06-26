package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.plaid.PlaidApiBudget
import com.family.bankapp.plaid.PlaidUsage
import com.family.bankapp.sync.SupabaseSharedStateClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository

    val defaultReminderDays = app.settingsRepository.defaultReminderDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val forecastDays = app.settingsRepository.forecastDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14)

    val plaidItemLimit = app.settingsRepository.plaidItemLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    val localPlaidCount = repository.observePlaidConnectedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _plaidUsage = MutableStateFlow<PlaidUsage?>(null)
    val plaidUsage: StateFlow<PlaidUsage?> = _plaidUsage.asStateFlow()

    private val _plaidUsageError = MutableStateFlow<String?>(null)
    val plaidUsageError: StateFlow<String?> = _plaidUsageError.asStateFlow()

    private val _plaidApiBudget = MutableStateFlow<PlaidApiBudget?>(null)
    val plaidApiBudget: StateFlow<PlaidApiBudget?> = _plaidApiBudget.asStateFlow()

    fun setReminderDays(days: Int) {
        viewModelScope.launch { app.settingsRepository.setDefaultReminderDays(days) }
    }

    fun setForecastDays(days: Int) {
        viewModelScope.launch { app.settingsRepository.setForecastDays(days) }
    }

    fun refreshPlaidUsage() {
        viewModelScope.launch {
            _plaidUsageError.value = null
            val config = FamilyAppConfig.supabaseConfig()
            SupabaseSharedStateClient.fetchPlaidUsage(config)
                .onSuccess { _plaidUsage.value = it }
                .onFailure { e ->
                    _plaidUsage.value = null
                    _plaidUsageError.value = e.message ?: "Could not reach Supabase"
                }
            SupabaseSharedStateClient.fetchPlaidApiBudget(config)
                .onSuccess { _plaidApiBudget.value = it }
                .onFailure { _plaidApiBudget.value = null }
        }
    }
}
