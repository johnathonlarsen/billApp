package com.family.bankapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.family.bankapp.MainActivity
import com.family.bankapp.R
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.util.BillSchedule
import com.family.bankapp.util.MoneyFormatter

object NotificationHelper {
    const val CHANNEL_ID = "bill_reminders"
    const val CHANNEL_NAME = "Bill Reminders"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders for upcoming and overdue bills"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun showBillReminder(context: Context, bill: BillEntity, notificationId: Int) {
        createChannel(context)
        val dueInfo = BillSchedule.enrich(bill)
        val title = if (dueInfo.isOverdue) "Overdue: ${bill.name}" else "Upcoming bill: ${bill.name}"
        val text = when {
            dueInfo.isOverdue -> "Was due ${-dueInfo.daysUntilDue} day(s) ago · ${MoneyFormatter.format(bill.amountCents)}"
            dueInfo.daysUntilDue == 0L -> "Due today · ${MoneyFormatter.format(bill.amountCents)}"
            else -> "Due in ${dueInfo.daysUntilDue} day(s) · ${MoneyFormatter.format(bill.amountCents)}"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
