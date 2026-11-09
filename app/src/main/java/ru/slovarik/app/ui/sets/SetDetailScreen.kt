package ru.slovarik.app.ui.sets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.slovarik.app.data.BackupManager
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetWithCounts
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.ConfirmDialog
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.components.Languages
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.ui.theme.StatusColors
import ru.slovarik.app.util.cardsCount
import ru.slovarik.app.util.rememberTts
import java.io.File
import javax.inject.Inject

data class SetDetailUi(
    val loading: Boolean = true,
    val item: SetWithCounts? = null,
    val cards: List<CardEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
)

@HiltViewModel
class SetDetailViewModel @Inject constructor(
    private val repository: Repository,
    private val backup: BackupManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L

    val state: StateFlow<SetDetailUi> = combine(
        repository.observeSet(setId),
        repository.observeCards(setId),
        repository.observeFolders(),
    ) { item, cards, folders ->
        SetDetailUi(loading = false, item = item, cards = cards, folders = folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetDetailUi())

    val message = MutableStateFlow<String?>(null)

    fun imageFile(name: String): File = repository.imageFile(name)

    fun toggleFavorite(card: CardEntity) {
        viewModelScope.launch { repository.setFavorite(card.id, !card.favorite) }
    }

    fun moveToFolder(folderId: Long?) {
        viewModelScope.launch { repository.moveSet(setId, folderId) }
    }

    fun resetProgress() {
        viewModelScope.launch {
            repository.resetProgress(setId)
            message.value = "Прогресс сброшен"
        }
    }

    fun deleteSet(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSet(setId)
            onDone()
        }
    }

    fun export(uri: Uri, csv: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (csv) backup.exportSetAsCsv(setId, uri) else backup.exportSetAsText(setId, uri)
            }.onSuccess {
                message.value = "Набор сохранён в файл"
            }.onFailure {
                message.value = "Не удалось сохранить файл"
            }
        }
    }
}

@Composable
fun SetDetailScreen(nav: NavController, viewModel: SetDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val tts = rememberTts()

    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }

    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.export(uri, csv = false)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri, csv = true)
    }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.message.value = null
        }
    }

    val setInfo = state.item
    val setId = viewModel.setId
    val fileName = setInfo?.set?.name?.replace(Regex("[^\\p{L}\\p{N} _-]"), "")?.trim().orEmpty().ifEmpty { "nabor" }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = setInfo?.set?.name.orEmpty(),
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem("Изменить набор", Icons.Filled.Edit) {
                            menuOpen = false
                            nav.navigate(Routes.setEdit(setId = setId))
                        }
                        MenuItem("Переместить в папку", Icons.Filled.Folder) {
                            menuOpen = false
                            showMove = true
                        }
                        MenuItem("Импорт из текста", Icons.Filled.FileDownload) {
                            menuOpen = false
                            nav.navigate(Routes.import(setId))
                        }
                        MenuItem("Экспорт в текст", Icons.Filled.FileUpload) {
                            menuOpen = false
                            exportText.launch("$fileName.txt")
                        }
                        MenuItem("Экспорт в CSV", Icons.Filled.FileUpload) {
                            menuOpen = false
                            exportCsv.launch("$fileName.csv")
                        }
                        MenuItem("Сбросить прогресс", Icons.Filled.Refresh) {
                            menuOpen = false
                            showReset = true
                        }
                        MenuItem("Удалить набор", Icons.Filled.Delete) {
                            menuOpen = false
                            showDelete = true
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.cardEdit(setId)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Карточки") },
            )
        },
    ) { padding ->
        if (state.loading || setInfo == null) return@Scaffold
        val hasCards = state.cards.isNotEmpty()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    if (setInfo.set.description.isNotBlank()) {
                        Text(setInfo.set.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "${Languages.name(setInfo.set.termLang)} → ${Languages.name(setInfo.set.defLang)} · " +
                            "${cardsCount(setInfo.cardCount)} · выучено ${setInfo.learnedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { setInfo.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeButton("Карточки", Icons.Filled.Style, hasCards, Modifier.weight(1f)) {
                            nav.navigate(Routes.flashcards(setId))
                        }
                        ModeButton("Заучивание", Icons.Filled.School, hasCards, Modifier.weight(1f)) {
                            nav.navigate(Routes.learn(setId))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeButton("Контрольная", Icons.Filled.Quiz, hasCards, Modifier.weight(1f)) {
                            nav.navigate(Routes.test(setId))
                        }
                        ModeButton("Сопоставление", Icons.Filled.Extension, state.cards.size >= 2, Modifier.weight(1f)) {
                            nav.navigate(Routes.match(setId))
                        }
                    }
                }
            }

            item { SectionTitle("Карточки") }

            if (!hasCards) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = "В наборе пока нет карточек",
                        text = "Добавляйте слова по одному или вставьте готовый список.",
                    ) {
                        OutlinedButton(onClick = { nav.navigate(Routes.import(setId)) }) {
                            Text("Импорт из текста")
                        }
                    }
                }
            } else {
                items(state.cards, key = { it.id }) { card ->
                    CardRow(
                        card = card,
                        image = card.imagePath?.let(viewModel::imageFile),
                        onClick = { nav.navigate(Routes.cardEdit(setId, card.id)) },
                        onSpeak = { tts.speak(card.term, setInfo.set.termLang) },
                        onFavorite = { viewModel.toggleFavorite(card) },
                    )
                }
            }
        }
    }

    if (showDelete) {
        ConfirmDialog(
            title = "Удалить набор?",
            text = "Набор и все его карточки будут удалены без возможности восстановления.",
            onConfirm = { viewModel.deleteSet { nav.popBackStack() } },
            onDismiss = { showDelete = false },
        )
    }
    if (showReset) {
        ConfirmDialog(
            title = "Сбросить прогресс?",
            text = "Статистика и уровни знания всех карточек набора обнулятся.",
            confirmText = "Сбросить",
            onConfirm = { viewModel.resetProgress() },
            onDismiss = { showReset = false },
        )
    }
    if (showMove) {
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Переместить в папку") },
            text = {
                Column {
                    val current = setInfo?.set?.folderId
                    FolderChoice("Без папки", current == null) {
                        viewModel.moveToFolder(null)
                        showMove = false
                    }
                    state.folders.forEach { folder ->
                        FolderChoice(folder.name, current == folder.id) {
                            viewModel.moveToFolder(folder.id)
                            showMove = false
                        }
                    }
                    if (state.folders.isEmpty()) {
                        Text(
                            "Папок пока нет — создайте их на вкладке «Папки».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMove = false }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun MenuItem(title: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun FolderChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title)
    }
}

@Composable
private fun ModeButton(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CardRow(
    card: CardEntity,
    image: File?,
    onClick: () -> Unit,
    onSpeak: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelDot(card)
                    Spacer(Modifier.width(8.dp))
                    Text(card.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(card.definition, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (card.example.isNotBlank()) {
                    Text(
                        card.example,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            IconButton(onClick = onSpeak) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить")
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (card.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Избранное",
                    tint = if (card.favorite) StatusColors.warning else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Цветная точка уровня знания: серая — новое, жёлтая — учу, зелёная — выучено. */
@Composable
private fun LevelDot(card: CardEntity) {
    val color = when {
        card.isNew -> MaterialTheme.colorScheme.outlineVariant
        card.isLearned -> StatusColors.correct
        else -> StatusColors.warning
    }
    Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(10.dp)) {}
}
