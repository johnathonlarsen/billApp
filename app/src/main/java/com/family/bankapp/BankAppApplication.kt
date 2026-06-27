package com.family.bankapp

import android.app.Application
import com.family.bankapp.data.AppDatabase
import com.family.bankapp.data.repository.BankRepository
import com.family.bankapp.data.settings.SettingsRepository
import com.family.bankapp.notifications.NotificationHelper
import com.family.bankapp.notifications.ReminderScheduler

class BankAppApplication : Application() {
    lateinit var repository: BankRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

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
