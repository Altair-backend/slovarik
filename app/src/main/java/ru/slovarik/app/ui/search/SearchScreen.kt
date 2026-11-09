package ru.slovarik.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.EmptyState
import javax.inject.Inject

data class SearchResult(val card: CardEntity, val setName: String)

@HiltViewModel
class SearchViewModel @Inject constructor(repository: Repository) : ViewModel() {

    var query by mutableStateOf("")
        private set

    private val queryFlow = MutableStateFlow("")

    /**
     * Поиск идёт по термину, переводу и примеру. Фильтруем в Kotlin, а не в SQL:
     * LIKE в SQLite не умеет сравнивать кириллицу без учёта регистра.
     */
    val results: StateFlow<List<SearchResult>> = combine(
        queryFlow.debounce(150),
        repository.observeAllCards(),
        repository.observeSets(),
    ) { text, cards, sets ->
        val needle = text.trim()
        if (needle.isEmpty()) {
            emptyList()
        } else {
            val names = sets.associate { it.set.id to it.set.name }
            cards.asSequence()
                .filter {
                    it.term.contains(needle, ignoreCase = true) ||
                        it.definition.contains(needle, ignoreCase = true) ||
                        it.example.contains(needle, ignoreCase = true)
                }
                .take(200)
                .map { SearchResult(it, names[it.setId].orEmpty()) }
                .toList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query = value
        queryFlow.value = value
    }
}

@Composable
fun SearchScreen(nav: NavController, viewModel: SearchViewModel = hiltViewModel()) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    TextField(
                        value = viewModel.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Поиск по всем словам") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                        trailingIcon = {
                            if (viewModel.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Очистить")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            when {
                viewModel.query.isBlank() -> item {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "Поиск по всем наборам",
                        text = "Введите слово, перевод или часть примера.",
                    )
                }
                results.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "Ничего не найдено",
                        text = "Попробуйте другое написание.",
                    )
                }
                else -> items(results, key = { it.card.id }) { result ->
                    ListItem(
                        headlineContent = { Text(result.card.term, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(result.card.definition) },
                        overlineContent = { Text(result.setName) },
                        modifier = Modifier.clickable { nav.navigate(Routes.set(result.card.setId)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
