package ru.slovarik.app.ui.sets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.ConfirmDialog
import ru.slovarik.app.util.cardsCount
import java.io.File
import javax.inject.Inject

/**
 * Экран карточки. Для новой карточки работает как «быстрое добавление»:
 * термин → перевод → Enter сохраняет и сразу открывает пустую следующую.
 */
@HiltViewModel
class CardEditViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L
    private val cardId: Long = savedStateHandle.get<Long>(Routes.ARG_CARD) ?: -1L
    val isNew: Boolean = cardId <= 0

    private var original: CardEntity? = null

    var term by mutableStateOf("")
    var definition by mutableStateOf("")
    var example by mutableStateOf("")
    var imageName by mutableStateOf<String?>(null)
        private set

    /** Сколько карточек добавлено за этот заход (для режима быстрого добавления). */
    var addedCount by mutableIntStateOf(0)
        private set

    val canSave: Boolean get() = term.isNotBlank() && definition.isNotBlank()

    init {
        if (!isNew) {
            viewModelScope.launch {
                repository.getCard(cardId)?.let { card ->
                    original = card
                    term = card.term
                    definition = card.definition
                    example = card.example
                    imageName = card.imagePath
                }
            }
        }
    }

    fun imageFile(name: String): File = repository.imageFile(name)

    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            val name = repository.importImage(uri) ?: return@launch
            removeUnsavedImage()
            imageName = name
        }
    }

    fun removeImage() {
        viewModelScope.launch {
            removeUnsavedImage()
            imageName = null
        }
    }

    /** Файл, который выбрали, но так и не сохранили в карточку, удаляем сразу. */
    private suspend fun removeUnsavedImage() {
        val current = imageName ?: return
        if (current != original?.imagePath) repository.deleteImage(current)
    }

    /** Сохраняет карточку. Для новой — очищает поля под следующую, для существующей вызывает onClose. */
    fun save(onClose: () -> Unit) {
        if (!canSave) return
        viewModelScope.launch {
            val existing = original
            if (existing == null) {
                repository.saveCard(
                    CardEntity(
                        setId = setId,
                        term = term.trim(),
                        definition = definition.trim(),
                        example = example.trim(),
                        imagePath = imageName,
                    ),
                )
                term = ""
                definition = ""
                example = ""
                imageName = null
                addedCount++
            } else {
                val oldImage = existing.imagePath
                repository.saveCard(
                    existing.copy(
                        term = term.trim(),
                        definition = definition.trim(),
                        example = example.trim(),
                        imagePath = imageName,
                    ),
                )
                if (oldImage != null && oldImage != imageName) repository.deleteImage(oldImage)
                onClose()
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val card = original ?: return
        viewModelScope.launch {
            repository.deleteCard(card)
            onDone()
        }
    }
}

@Composable
fun CardEditScreen(nav: NavController, viewModel: CardEditViewModel = hiltViewModel()) {
    val termFocus = remember { FocusRequester() }
    val definitionFocus = remember { FocusRequester() }
    var showDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickImage(uri)
    }

    // в режиме добавления курсор сразу в поле термина — и возвращается туда после каждого сохранения
    LaunchedEffect(viewModel.addedCount) {
        if (viewModel.isNew) termFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (viewModel.isNew) "Новые карточки" else "Карточка",
                onBack = { nav.popBackStack() },
                actions = {
                    if (!viewModel.isNew) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить карточку")
                        }
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (viewModel.isNew && viewModel.addedCount > 0) {
                Text(
                    "Добавлено: ${cardsCount(viewModel.addedCount)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedTextField(
                value = viewModel.term,
                onValueChange = { viewModel.term = it },
                label = { Text("Термин") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { definitionFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(termFocus),
            )
            OutlinedTextField(
                value = viewModel.definition,
                onValueChange = { viewModel.definition = it },
                label = { Text("Перевод или определение") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (viewModel.isNew) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onNext = { viewModel.save { nav.popBackStack() } },
                    onDone = { viewModel.save { nav.popBackStack() } },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(definitionFocus),
            )
            OutlinedTextField(
                value = viewModel.example,
                onValueChange = { viewModel.example = it },
                label = { Text("Пример (необязательно)") },
                modifier = Modifier.fillMaxWidth(),
            )

            val imageName = viewModel.imageName
            if (imageName == null) {
                OutlinedButton(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить картинку")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = viewModel.imageFile(imageName),
                        contentDescription = "Картинка карточки",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { viewModel.removeImage() }) { Text("Убрать картинку") }
                }
            }

            Button(
                onClick = { viewModel.save { nav.popBackStack() } },
                enabled = viewModel.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.isNew) "Сохранить и добавить следующую" else "Сохранить")
            }
            if (viewModel.isNew) {
                Text(
                    "Подсказка: «Далее» на клавиатуре в поле перевода сохраняет карточку и открывает следующую.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Готово")
                }
            }
        }
    }

    if (showDelete) {
        ConfirmDialog(
            title = "Удалить карточку?",
            text = "Карточка и её статистика будут удалены.",
            onConfirm = { viewModel.delete { nav.popBackStack() } },
            onDismiss = { showDelete = false },
        )
    }
}
