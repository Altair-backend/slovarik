package ru.slovarik.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Несколько раз в день меняет слово в виджетах «Слово дня» и обновляет виджет «Прогресс». */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(WordWidget::class.java).forEach { glanceId ->
                WordWidget.showNextWord(context, glanceId)
            }
            ProgressWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "widget_update"
        private const val INTERVAL_HOURS = 6L // 4 раза в сутки

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Обновить виджет «Прогресс» прямо сейчас (после занятия). */
        suspend fun refreshProgressNow(context: Context) {
            runCatching { ProgressWidget().updateAll(context) }
        }
    }
}
