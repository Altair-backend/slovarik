package ru.slovarik.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ru.slovarik.app.data.AppSettings
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetEntity
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.FolderBadge
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.ui.theme.SlovarikTheme
import javax.inject.Inject

/** Экран, который система открывает при добавлении виджета «Слово дня»: выбор набора или папки. */
@AndroidEntryPoint
class WordWidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var repository: Repository
    @Inject lateinit var settings: SettingsStore

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var folders by mutableStateOf<List<FolderEntity>>(emptyList())
    private var sets by mutableStateOf<List<SetEntity>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // если пользователь уйдёт без выбора — виджет не добавится
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        lifecycleScope.launch {
            folders = repository.getFolders()
            sets = repository.getSets()
        }

        setContent {
            val current by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            SlovarikTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
                Scaffold(
                    topBar = { AppTopBar(title = "Слова для виджета", onBack = { finish() }) },
                ) { padding ->
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        item {
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.AllInclusive, contentDescription = null) },
                                headlineContent = { Text("Все слова") },
                                supportingContent = { Text("Случайное слово из всех наборов") },
                                modifier = Modifier.clickable { choose(Repository.SOURCE_ALL, 0L, "Слово дня") },
                            )
                        }
                        if (folders.isNotEmpty()) {
                            item { SectionTitle("Папки", Modifier.padding(horizontal = 16.dp)) }
                            items(folders, key = { "f${it.id}" }) { folder ->
                                ListItem(
                                    leadingContent = { FolderBadge(folder) },
                                    headlineContent = { Text(folder.name) },
                                    modifier = Modifier.clickable {
                                        choose(Repository.SOURCE_FOLDER, folder.id, folder.name)
                                    },
                                )
                            }
                        }
                        if (sets.isNotEmpty()) {
                            item { SectionTitle("Наборы", Modifier.padding(horizontal = 16.dp)) }
                            items(sets, key = { "s${it.id}" }) { set ->
                                ListItem(
                                    leadingContent = { Icon(Icons.Filled.Style, contentDescription = null) },
                                    headlineContent = { Text(set.name) },
                                    modifier = Modifier.clickable {
                                        choose(Repository.SOURCE_SET, set.id, set.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Сохраняет выбор в состояние виджета, показывает первое слово и сообщает системе об успехе. */
    private fun choose(sourceType: String, sourceId: Long, sourceName: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WordWidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WordWidgetConfigActivity, glanceId) { prefs ->
                prefs[WordWidget.SOURCE_TYPE] = sourceType
                prefs[WordWidget.SOURCE_ID] = sourceId
                prefs[WordWidget.SOURCE_NAME] = sourceName
                prefs.remove(WordWidget.CARD_ID)
            }
            WordWidget.showNextWord(this@WordWidgetConfigActivity, glanceId)

            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
