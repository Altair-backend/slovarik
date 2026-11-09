package ru.slovarik.app.ui.folders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetWithCounts
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.ConfirmDialog
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.components.FolderBadge
import ru.slovarik.app.ui.components.FolderDialog
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.ui.components.SetCard
import ru.slovarik.app.util.plural
import javax.inject.Inject

data class FolderItem(val folder: FolderEntity, val setCount: Int)

data class FoldersUi(
    val loading: Boolean = true,
    val folders: List<FolderItem> = emptyList(),
    val looseSets: List<SetWithCounts> = emptyList(),
)

@HiltViewModel
class FoldersViewModel @Inject constructor(private val repository: Repository) : ViewModel() {

    val state: StateFlow<FoldersUi> = combine(repository.observeFolders(), repository.observeSets()) { folders, sets ->
        FoldersUi(
            loading = false,
            folders = folders.map { folder -> FolderItem(folder, sets.count { it.set.folderId == folder.id }) },
            looseSets = sets.filter { it.set.folderId == null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUi())

    fun createFolder(name: String, color: Int, icon: Int) {
        viewModelScope.launch { repository.saveFolder(FolderEntity(name = name, color = color, icon = icon)) }
    }
}

@Composable
fun FoldersScreen(nav: NavController, viewModel: FoldersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Папки",
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Поиск")
                    }
                    IconButton(onClick = { nav.navigate(Routes.setEdit()) }) {
                        Icon(Icons.Filled.PostAdd, contentDescription = "Новый набор")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                text = { Text("Папка") },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.folders.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title = "Папок пока нет",
                        text = "Папки помогают собрать наборы по темам: язык, учебник, уровень.",
                    )
                }
            } else {
                items(state.folders, key = { "f${it.folder.id}" }) { item ->
                    FolderRow(item, onClick = { nav.navigate(Routes.folder(item.folder.id)) })
                }
            }

            if (state.looseSets.isNotEmpty()) {
                item { SectionTitle("Наборы без папки") }
                items(state.looseSets, key = { "s${it.set.id}" }) { set ->
                    SetCard(set, onClick = { nav.navigate(Routes.set(set.set.id)) })
                }
            }
        }
    }

    if (showCreate) {
        FolderDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onSave = { name, color, icon ->
                viewModel.createFolder(name, color, icon)
                showCreate = false
            },
        )
    }
}

@Composable
private fun FolderRow(item: FolderItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderBadge(item.folder)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.setCount} ${plural(item.setCount, "набор", "набора", "наборов")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Экран одной папки
// ---------------------------------------------------------------------------------------------

data class FolderDetailUi(
    val loading: Boolean = true,
    val folder: FolderEntity? = null,
    val sets: List<SetWithCounts> = emptyList(),
)

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val folderId: Long = savedStateHandle.get<Long>(Routes.ARG_FOLDER) ?: 0L

    val state: StateFlow<FolderDetailUi> =
        combine(repository.observeFolder(folderId), repository.observeSets()) { folder, sets ->
            FolderDetailUi(loading = false, folder = folder, sets = sets.filter { it.set.folderId == folderId })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderDetailUi())

    fun update(name: String, color: Int, icon: Int) {
        val folder = state.value.folder ?: return
        viewModelScope.launch { repository.saveFolder(folder.copy(name = name, color = color, icon = icon)) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            onDone()
        }
    }
}

@Composable
fun FolderDetailScreen(nav: NavController, viewModel: FolderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val folder = state.folder

    Scaffold(
        topBar = {
            AppTopBar(
                title = folder?.name.orEmpty(),
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Изменить папку")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить папку")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.setEdit(folderId = viewModel.folderId)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Набор") },
            )
        },
    ) { padding ->
        if (state.loading || folder == null) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.sets.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = "В папке пока пусто",
                        text = "Создайте набор здесь или переместите существующий: откройте набор → меню → «Переместить в папку».",
                    )
                }
            } else {
                items(state.sets, key = { it.set.id }) { set ->
                    SetCard(set, onClick = { nav.navigate(Routes.set(set.set.id)) })
                }
            }
        }
    }

    if (showEdit && folder != null) {
        FolderDialog(
            initial = folder,
            onDismiss = { showEdit = false },
            onSave = { name, color, icon ->
                viewModel.update(name, color, icon)
                showEdit = false
            },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Удалить папку?",
            text = "Наборы из папки не удалятся — они останутся в разделе «Наборы без папки».",
            onConfirm = { viewModel.delete { nav.popBackStack() } },
            onDismiss = { showDelete = false },
        )
    }
}
