package com.family.bankapp

import android.app.Application
import com.family.bankapp.data.AppDatabase
import com.family.bankapp.data.repository.BankRepository
import com.family.bankapp.data.settings.SettingsRepository
import com.family.bankapp.notifications.NotificationHelper
import com.family.bankapp.notifications.ReminderScheduler

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BankAppApplication : Application() {
    lateinit var repository: BankRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    private val _plaidUsageRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val plaidUsageRefreshRequests = _plaidUsageRefreshRequests.asSharedFlow()

    /** Ask all Plaid usage UI to re-fetch Supabase slot + API counters. */
    fun requestPlaidUsageRefresh() {
        _plaidUsageRefreshRequests.tryEmit(Unit)
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = BankRepository(
            db.bankDao(),
            db.accountDao(),
            db.billDao(),
            db.paymentRecordDao(),
            db.plaidTransactionDao(),
            db.incomeDao()
        )
        settingsRepository = SettingsRepository(this)
        NotificationHelper.createChannel(this)
        ReminderScheduler.schedule(this)
    }
}
