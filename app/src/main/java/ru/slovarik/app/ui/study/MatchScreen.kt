package ru.slovarik.app.ui.study

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.theme.StatusColors
import ru.slovarik.app.util.Time
import javax.inject.Inject

data class MatchTile(
    val id: Int,
    val cardId: Long,
    val text: String,
    val isTerm: Boolean,
    val matched: Boolean = false,
)

data class MatchUi(
    val loading: Boolean = true,
    val notEnough: Boolean = false,
    val tiles: List<MatchTile> = emptyList(),
    val selected: Int? = null,
    /** Плитки неверной пары — кратко подсвечиваются красным. */
    val wrong: Set<Int> = emptySet(),
    val elapsedMs: Long = 0,
    val penalties: Int = 0,
    val finished: Boolean = false,
    val bestMs: Long? = null,
    val newRecord: Boolean = false,
)

/**
 * Игра на время: найти все пары «термин — перевод».
 * За каждую ошибку к времени добавляется штрафная секунда.
 */
@HiltViewModel
class MatchViewModel @Inject constructor(
    private val repository: Repository,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L
    private var cards: List<CardEntity> = emptyList()
    private var timer: Job? = null
    private var startedAt = 0L
    private var penaltyMs = 0L

    private val _state = MutableStateFlow(MatchUi())
    val state: StateFlow<MatchUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            cards = repository.getCards(setId)
            start()
        }
    }

    fun start() {
        viewModelScope.launch {
            val chosen = cards.shuffled()
                .distinctBy { it.term.lowercase() }
                .distinctBy { it.definition.lowercase() }
                .take(PAIRS)
            val best = settings.bestMatchTime(setId).first()
            if (chosen.size < 2) {
                _state.value = MatchUi(loading = false, notEnough = true, bestMs = best)
                return@launch
            }
            val tiles = chosen
                .flatMap { listOf(Triple(it.id, it.term, true), Triple(it.id, it.definition, false)) }
                .shuffled()
                .mapIndexed { index, (cardId, text, isTerm) -> MatchTile(index, cardId, text, isTerm) }
            penaltyMs = 0
            _state.value = MatchUi(loading = false, tiles = tiles, bestMs = best)
            startTimer()
        }
    }

    private fun startTimer() {
        timer?.cancel()
        startedAt = SystemClock.elapsedRealtime()
        timer = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt + penaltyMs) }
                delay(100)
            }
        }
    }

    fun tap(tileId: Int) {
        val current = _state.value
        if (current.finished || current.wrong.isNotEmpty()) return
        val tile = current.tiles.firstOrNull { it.id == tileId } ?: return
        if (tile.matched) return

        val selectedId = current.selected
        if (selectedId == null) {
            _state.update { it.copy(selected = tileId) }
            return
        }
        if (selectedId == tileId) {
            _state.update { it.copy(selected = null) }
            return
        }

        val first = current.tiles.first { it.id == selectedId }
        if (first.cardId == tile.cardId && first.isTerm != tile.isTerm) {
            val tiles = current.tiles.map { if (it.id == tileId || it.id == selectedId) it.copy(matched = true) else it }
            _state.update { it.copy(tiles = tiles, selected = null) }
            if (tiles.all { it.matched }) finish()
        } else {
            penaltyMs += PENALTY_MS
            _state.update { it.copy(wrong = setOf(tileId, selectedId), penalties = it.penalties + 1) }
            viewModelScope.launch {
                delay(450)
                _state.update { it.copy(wrong = emptySet(), selected = null) }
            }
        }
    }

    private fun finish() {
        timer?.cancel()
        val total = SystemClock.elapsedRealtime() - startedAt + penaltyMs
        val previousBest = _state.value.bestMs
        val record = previousBest == null || total < previousBest
        _state.update {
            it.copy(
                finished = true,
                elapsedMs = total,
                newRecord = record,
                bestMs = if (record) total else previousBest,
            )
        }
        val pairs = _state.value.tiles.size / 2
        viewModelScope.launch {
            settings.saveBestMatchTime(setId, total)
            repository.logActivity(reviews = pairs, correct = pairs, setId = setId)
        }
    }

    companion object {
        private const val PAIRS = 6
        private const val PENALTY_MS = 1000L
    }
}

@Composable
fun MatchScreen(nav: NavController, viewModel: MatchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppTopBar(title = "Сопоставление", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when {
                state.loading -> Unit

                state.notEnough -> EmptyState(
                    icon = Icons.Filled.Extension,
                    title = "Нужно минимум две карточки",
                    text = "Добавьте в набор ещё слова, чтобы играть.",
                )

                state.finished -> {
                    SummaryBlock(
                        title = if (state.newRecord) "Новый рекорд: ${Time.formatDuration(state.elapsedMs)}" else "Время: ${Time.formatDuration(state.elapsedMs)}",
                        text = buildString {
                            if (state.penalties > 0) append("Ошибок: ${state.penalties} (+${state.penalties} сек)\n")
                            state.bestMs?.let { append("Лучшее время: ${Time.formatDuration(it)}") }
                        },
                        primaryText = "Сыграть ещё раз",
                        onPrimary = viewModel::start,
                        secondaryText = "К набору",
                        onSecondary = { nav.popBackStack() },
                    )
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Time.formatDuration(state.elapsedMs),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        state.bestMs?.let {
                            Text(
                                "Рекорд ${Time.formatDuration(it)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.tiles, key = { it.id }) { tile ->
                            TileView(
                                tile = tile,
                                selected = state.selected == tile.id,
                                wrong = tile.id in state.wrong,
                                onClick = { viewModel.tap(tile.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: MatchTile, selected: Boolean, wrong: Boolean, onClick: () -> Unit) {
    val target = when {
        wrong -> StatusColors.wrong
        tile.matched -> StatusColors.correct
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val border by animateColorAsState(target, label = "tileBorder")
    val alpha by animateFloatAsState(if (tile.matched) 0f else 1f, label = "tileAlpha")

    OutlinedCard(
        onClick = onClick,
        enabled = !tile.matched,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .alpha(alpha),
        border = BorderStroke(if (selected || wrong) 2.dp else 1.dp, border),
        colors = CardDefaults.outlinedCardColors(
            containerColor = when {
                wrong -> StatusColors.wrong.copy(alpha = 0.15f)
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            },
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tile.text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
