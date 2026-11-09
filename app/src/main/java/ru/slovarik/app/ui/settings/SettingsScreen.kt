package ru.slovarik.app.ui.settings

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.slovarik.app.data.AppSettings
import ru.slovarik.app.data.BackupManager
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.notifications.ReminderWorker
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.ConfirmDialog
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.widget.WidgetUpdateWorker
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val backup: BackupManager,
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val message = MutableStateFlow<String?>(null)

    fun setThemeMode(mode: Int) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    /** Включает или выключает напоминание и перепланирует задачу WorkManager. */
    fun setReminder(enabled: Boolean, hour: Int = state.value.reminderHour, minute: Int = state.value.reminderMinute) {
        viewModelScope.launch {
            settings.setReminder(enabled, hour, minute)
            if (enabled) {
                ReminderWorker.schedule(context, hour, minute, replace = true)
            } else {
                ReminderWorker.cancel(context)
            }
        }
    }

    fun notificationsDenied() {
        message.value = "Без разрешения на уведомления напоминания не придут"
    }

    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { backup.createBackup(uri) }
                .onSuccess { message.value = "Резервная копия сохранена" }
                .onFailure { message.value = "Не удалось сохранить копию" }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { backup.restoreBackup(uri) }
                .onSuccess {
                    WidgetUpdateWorker.refreshProgressNow(context)
                    message.value = "Данные восстановлены"
                }
                .onFailure { error ->
                    message.value = if (error is IllegalArgumentException) {
                        error.message
                    } else {
                        "Не удалось прочитать файл копии"
                    }
                }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showTimePicker by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setReminder(true) else viewModel.notificationsDenied()
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.createBackup(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.message.value = null
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Настройки") },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle("Оформление", Modifier.padding(horizontal = 16.dp))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AppSettings.THEME_SYSTEM to "Как в системе",
                    AppSettings.THEME_LIGHT to "Светлая",
                    AppSettings.THEME_DARK to "Тёмная",
                ).forEach { (mode, title) ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(title) },
                    )
                }
            }
            val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ListItem(
                leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                headlineContent = { Text("Динамические цвета") },
                supportingContent = {
                    Text(if (dynamicSupported) "Material You: цвета берутся из обоев" else "Доступно с Android 12")
                },
                trailingContent = {
                    Switch(
                        checked = state.dynamicColor && dynamicSupported,
                        onCheckedChange = viewModel::setDynamicColor,
                        enabled = dynamicSupported,
                    )
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Напоминание", Modifier.padding(horizontal = 16.dp))
            ListItem(
                leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                headlineContent = { Text("Ежедневное напоминание") },
                supportingContent = { Text("Уведомление «пора повторить слова»") },
                trailingContent = {
                    Switch(
                        checked = state.reminderEnabled,
                        onCheckedChange = { enabled ->
                            when {
                                !enabled -> viewModel.setReminder(false)
                                // на Android 13+ уведомления нужно разрешить явно
                                !ReminderWorker.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else -> viewModel.setReminder(true)
                            }
                        },
                    )
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                headlineContent = { Text("Время напоминания") },
                supportingContent = { Text("%02d:%02d".format(state.reminderHour, state.reminderMinute)) },
                modifier = Modifier.clickable { showTimePicker = true },
            )
            Text(
                "Система может показать уведомление на несколько минут позже — так Android экономит заряд.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Данные", Modifier.padding(horizontal = 16.dp))
            ListItem(
                leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                headlineContent = { Text("Создать резервную копию") },
                supportingContent = { Text("Все папки, наборы, карточки, картинки и статистика — в один файл") },
                modifier = Modifier.clickable { createBackup.launch("slovarik-${LocalDate.now()}.zip") },
            )
            ListItem(
                leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null) },
                headlineContent = { Text("Восстановить из копии") },
                supportingContent = { Text("Текущие данные будут заменены данными из файла") },
                modifier = Modifier.clickable {
                    openBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Словарик 1.0 · работает полностью офлайн, данные хранятся только на этом устройстве.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = state.reminderHour,
            initialMinute = state.reminderMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Время напоминания") },
            text = { TimeInput(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminder(state.reminderEnabled, pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Отмена") } },
        )
    }

    restoreUri?.let { uri ->
        ConfirmDialog(
            title = "Восстановить данные?",
            text = "Все текущие папки, наборы и статистика будут заменены содержимым резервной копии.",
            confirmText = "Восстановить",
            onConfirm = { viewModel.restoreBackup(uri) },
            onDismiss = { restoreUri = null },
        )
    }
}
