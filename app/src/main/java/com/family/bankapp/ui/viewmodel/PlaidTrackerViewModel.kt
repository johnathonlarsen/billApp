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

/** Shared Plaid slot + API call counters from Supabase (both phones see the same numbers). */
class PlaidTrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository

    private val _itemUsage = MutableStateFlow<PlaidUsage?>(null)
    val itemUsage: StateFlow<PlaidUsage?> = _itemUsage.asStateFlow()

    private val _apiBudget = MutableStateFlow<PlaidApiBudget?>(null)
    val apiBudget: StateFlow<PlaidApiBudget?> = _apiBudget.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val localPlaidCount = repository.observePlaidConnectedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        refresh()
        viewModelScope.launch {
            app.plaidUsageRefreshRequests.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val config = FamilyAppConfig.supabaseConfig()

            SupabaseSharedStateClient.fetchPlaidUsage(config)
                .onSuccess { _itemUsage.value = it }
                .onFailure { e ->
                    _itemUsage.value = null
                    _error.value = e.message
                }

            SupabaseSharedStateClient.fetchPlaidApiBudget(config)
                .onSuccess { _apiBudget.value = it }
                .onFailure { e ->
                    _apiBudget.value = null
                    if (_error.value == null) {
                        _error.value = e.message ?: "Could not load API budget (run migration 003 in Supabase?)"
                    }
                }

            _loading.value = false
        }
    }
}
