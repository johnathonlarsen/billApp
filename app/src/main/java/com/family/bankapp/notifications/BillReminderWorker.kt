package com.family.bankapp.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.family.bankapp.data.AppDatabase
import com.family.bankapp.data.settings.SettingsRepository
import com.family.bankapp.util.BillSchedule
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class BillReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val defaultReminderDays = settings.defaultReminderDays.first()
        val bills = db.billDao().getActiveSync()

        bills.forEach { bill ->
            val dueInfo = BillSchedule.enrich(bill)
            if (dueInfo.isPaidThisCycle) return@forEach

            val reminderDays = bill.reminderDaysBefore.takeIf { it > 0 } ?: defaultReminderDays
            val shouldNotify = dueInfo.isOverdue ||
                (dueInfo.daysUntilDue in 0..reminderDays.toLong())

            if (shouldNotify) {
                NotificationHelper.showBillReminder(
                    applicationContext,
                    bill,
                    bill.id.toInt()
                )
            }
        }
        return Result.success()
    }
}

object ReminderScheduler {
    private const val WORK_NAME = "bill_reminder_check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BillReminderWorker>(12, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
