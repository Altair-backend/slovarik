package ru.slovarik.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.slovarik.app.MainActivity
import ru.slovarik.app.R
import ru.slovarik.app.di.appEntryPoint
import ru.slovarik.app.util.plural
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Ежедневное напоминание: WorkManager раз в сутки запускает воркер, который показывает уведомление. */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!canNotify(context)) return Result.success()

        val due = runCatching { context.appEntryPoint().repository().dueCountToday() }.getOrDefault(0)
        val text = if (due > 0) {
            "Сегодня нужно повторить $due ${plural(due, "слово", "слова", "слов")}"
        } else {
            "Пара минут занятий — и серия дней продолжится"
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора повторить слова")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // разрешение отозвали между проверкой и показом — просто пропускаем
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "reminder"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "daily_reminder"

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_reminder_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /**
         * Планирует напоминание на указанное время.
         * replace = true — пересоздать задачу (время изменилось), false — оставить существующую.
         */
        fun schedule(context: Context, hour: Int, minute: Int, replace: Boolean) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(hour, minute))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMillis()

            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
