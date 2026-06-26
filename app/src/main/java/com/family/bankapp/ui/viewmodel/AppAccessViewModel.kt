package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.FamilyAppConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppAccessViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = (application as BankAppApplication).settingsRepository

    val isActivated = settings.isAppActivated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun tryUnlock(password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = password == FamilyAppConfig.APP_ACCESS_PASSWORD
            if (ok) settings.setAppActivated(true)
            onResult(ok)
        }
    }
}
