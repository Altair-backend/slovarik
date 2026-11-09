package ru.slovarik.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.slovarik.app.data.DemoData
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.notifications.ReminderWorker
import ru.slovarik.app.widget.WidgetUpdateWorker
import javax.inject.Inject

@HiltAndroidApp
class SlovarikApp : Application() {

    @Inject lateinit var repository: Repository
    @Inject lateinit var settings: SettingsStore

    /** Область корутин на всё время жизни приложения. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ReminderWorker.createChannel(this)
        WidgetUpdateWorker.schedule(this)

        appScope.launch {
            // при первом запуске создаём демо-набор
            if (!settings.isSeeded()) {
                if (repository.isEmpty()) DemoData.insert(repository)
                settings.setSeeded()
            }
            // на случай, если система сбросила задачи: восстанавливаем напоминание
            val current = settings.settings.first()
            if (current.reminderEnabled) {
                ReminderWorker.schedule(this@SlovarikApp, current.reminderHour, current.reminderMinute, replace = false)
            }
        }
    }
}
