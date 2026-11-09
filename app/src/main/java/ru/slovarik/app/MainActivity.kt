package ru.slovarik.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ru.slovarik.app.data.AppSettings
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.ui.AppRoot
import ru.slovarik.app.ui.CardLink
import ru.slovarik.app.ui.theme.SlovarikTheme
import ru.slovarik.app.widget.WidgetUpdateWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsStore

    /** Карточка, которую нужно открыть по тапу на виджете. */
    private var pendingLink by mutableStateOf<CardLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val current by settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val appSettings: AppSettings? = current
            // пока настройки не прочитаны, ничего не рисуем — иначе тема «мигнёт»
            if (appSettings != null) {
                SlovarikTheme(themeMode = appSettings.themeMode, dynamicColor = appSettings.dynamicColor) {
                    AppRoot(
                        pendingLink = pendingLink,
                        onLinkConsumed = { pendingLink = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // после занятия цифры на виджете «Прогресс» должны обновиться сразу
        val app = application as SlovarikApp
        app.appScope.launch { WidgetUpdateWorker.refreshProgressNow(applicationContext) }
    }

    /** Виджет открывает приложение ссылкой вида slovarik://card/{setId}/{cardId}. */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "slovarik" || uri.host != "card") return
        val setId = uri.pathSegments.getOrNull(0)?.toLongOrNull() ?: return
        val cardId = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return
        pendingLink = CardLink(setId, cardId)
    }
}
