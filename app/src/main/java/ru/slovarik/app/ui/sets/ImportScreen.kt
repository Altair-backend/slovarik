package ru.slovarik.app.ui.sets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.domain.ImportParser
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.util.cardsCount
import javax.inject.Inject

/** Массовый импорт: вставленный текст разбирается построчно с выбранным разделителем. */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L

    var text by mutableStateOf("")
    var separator by mutableStateOf(ImportParser.Separator.DASH)

    fun import(onDone: () -> Unit) {
        val pairs = ImportParser.parse(text, separator)
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            repository.addCards(setId, pairs)
            onDone()
        }
    }
}

@Composable
fun ImportScreen(nav: NavController, viewModel: ImportViewModel = hiltViewModel()) {
    val parsed = remember(viewModel.text, viewModel.separator) {
        ImportParser.parse(viewModel.text, viewModel.separator)
    }
    val totalLines = remember(viewModel.text) { viewModel.text.lines().count { it.isNotBlank() } }

    Scaffold(
        topBar = { AppTopBar(title = "Импорт из текста", onBack = { nav.popBackStack() }) },
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
            Text(
                "Вставьте список: одна строка — одна карточка, слово и перевод разделены выбранным знаком.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Разделитель", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportParser.Separator.entries.forEach { option ->
                    FilterChip(
                        selected = viewModel.separator == option,
                        onClick = { viewModel.separator = option },
                        label = { Text(option.title) },
                    )
                }
            }
            OutlinedTextField(
                value = viewModel.text,
                onValueChange = { viewModel.text = it },
                placeholder = {
                    Text(
                        when (viewModel.separator) {
                            ImportParser.Separator.DASH -> "apple — яблоко\nhouse — дом"
                            ImportParser.Separator.TAB -> "apple\tяблоко\nhouse\tдом"
                            ImportParser.Separator.COMMA -> "apple, яблоко\nhouse, дом"
                        },
                    )
                },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            if (totalLines > 0) {
                Text(
                    "Распознано: ${cardsCount(parsed.size)} из $totalLines строк",
                    fontWeight = FontWeight.SemiBold,
                    color = if (parsed.size == totalLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                parsed.take(3).forEach { (term, definition) ->
                    Text("$term  →  $definition", style = MaterialTheme.typography.bodySmall)
                }
                if (parsed.size < totalLines) {
                    Text(
                        "Строки без разделителя будут пропущены. Проверьте, что выбран правильный знак.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { viewModel.import { nav.popBackStack() } },
                enabled = parsed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (parsed.isEmpty()) "Добавить" else "Добавить ${cardsCount(parsed.size)}")
            }
        }
    }
}
