package ru.slovarik.app.ui.sets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetEntity
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.DropdownField
import ru.slovarik.app.ui.components.Languages
import javax.inject.Inject

/** Создание и редактирование набора: название, описание, языки, папка. */
@HiltViewModel
class SetEditViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: -1L
    private val initialFolderId: Long = savedStateHandle.get<Long>(Routes.ARG_FOLDER) ?: -1L
    val isNew: Boolean = setId <= 0

    private var original: SetEntity? = null

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var termLang by mutableStateOf("en")
    var defLang by mutableStateOf("ru")
    var folderId by mutableStateOf<Long?>(initialFolderId.takeIf { it > 0 })
    var folders by mutableStateOf<List<FolderEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            folders = repository.getFolders()
            if (!isNew) {
                repository.getSet(setId)?.let { set ->
                    original = set
                    name = set.name
                    description = set.description
                    termLang = set.termLang
                    defLang = set.defLang
                    folderId = set.folderId
                }
            }
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val base = original ?: SetEntity(name = "")
            val id = repository.saveSet(
                base.copy(
                    name = name.trim(),
                    description = description.trim(),
                    termLang = termLang,
                    defLang = defLang,
                    folderId = folderId,
                ),
            )
            onSaved(id)
        }
    }
}

@Composable
fun SetEditScreen(nav: NavController, viewModel: SetEditViewModel = hiltViewModel()) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = if (viewModel.isNew) "Новый набор" else "Изменить набор",
                onBack = { nav.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                label = { Text("Название") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = viewModel.description,
                onValueChange = { viewModel.description = it },
                label = { Text("Описание (необязательно)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField(
                    label = "Язык термина",
                    options = Languages.all,
                    selected = viewModel.termLang,
                    onSelect = { viewModel.termLang = it },
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Язык перевода",
                    options = Languages.all,
                    selected = viewModel.defLang,
                    onSelect = { viewModel.defLang = it },
                    modifier = Modifier.weight(1f),
                )
            }
            DropdownField(
                label = "Папка",
                options = listOf<Pair<Long?, String>>(null to "Без папки") +
                    viewModel.folders.map { it.id to it.name },
                selected = viewModel.folderId,
                onSelect = { viewModel.folderId = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Языки нужны для озвучки слов. Их можно поменять позже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    viewModel.save { id ->
                        if (viewModel.isNew) {
                            // новый набор сразу открываем, а экран создания убираем из истории
                            nav.popBackStack()
                            nav.navigate(Routes.set(id))
                        } else {
                            nav.popBackStack()
                        }
                    }
                },
                enabled = viewModel.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.isNew) "Создать набор" else "Сохранить")
            }
        }
    }
}
