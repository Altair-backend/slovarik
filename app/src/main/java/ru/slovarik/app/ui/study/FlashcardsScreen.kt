package ru.slovarik.app.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.domain.Sm2
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.theme.StatusColors
import ru.slovarik.app.util.rememberTts
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class FlashcardsUi(
    val loading: Boolean = true,
    val setName: String = "",
    val termLang: String = "en",
    val defLang: String = "ru",
    val deck: List<CardEntity> = emptyList(),
    val index: Int = 0,
    val known: Int = 0,
    /** Карточки, отмеченные «ещё учу» в этом проходе. */
    val learningIds: List<Long> = emptyList(),
    val shuffle: Boolean = false,
    /** true — сначала показывается термин, false — перевод. */
    val termFirst: Boolean = true,
    val onlyFavorites: Boolean = false,
    val hasFavorites: Boolean = false,
) {
    val current: CardEntity? get() = deck.getOrNull(index)
    val finished: Boolean get() = !loading && deck.isNotEmpty() && index >= deck.size
}

@HiltViewModel
class FlashcardsViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L
    private val startCardId: Long = savedStateHandle.get<Long>(Routes.ARG_CARD) ?: -1L

    private var allCards: List<CardEntity> = emptyList()

    private val _state = MutableStateFlow(FlashcardsUi())
    val state: StateFlow<FlashcardsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val set = repository.getSet(setId)
            allCards = repository.getCards(setId)
            _state.update {
                it.copy(
                    loading = false,
                    setName = set?.name.orEmpty(),
                    termLang = set?.termLang ?: "en",
                    defLang = set?.defLang ?: "ru",
                    hasFavorites = allCards.any { card -> card.favorite },
                )
            }
            buildDeck(startId = startCardId)
        }
    }

    fun imageFile(name: String): File = repository.imageFile(name)

    /** Собирает колоду с учётом фильтров. only — оставить только эти карточки. */
    private fun buildDeck(startId: Long = -1, only: Set<Long>? = null) {
        val current = _state.value
        var cards = allCards
        if (only != null) {
            cards = cards.filter { it.id in only }
        } else if (current.onlyFavorites) {
            cards = cards.filter { it.favorite }
        }
        if (current.shuffle) cards = cards.shuffled()
        if (startId > 0) {
            // карточка, открытая с виджета, показывается первой
            val position = cards.indexOfFirst { it.id == startId }
            if (position > 0) cards = cards.drop(position) + cards.take(position)
        }
        _state.update { it.copy(deck = cards, index = 0, known = 0, learningIds = emptyList()) }
    }

    /** Свайп вправо (known = true) — «знаю», влево — «ещё учу». */
    fun swipe(known: Boolean) {
        val card = _state.value.current ?: return
        viewModelScope.launch {
            repository.review(card.id, if (known) Sm2.QUALITY_GOOD else Sm2.QUALITY_AGAIN)
        }
        _state.update {
            it.copy(
                index = it.index + 1,
                known = it.known + if (known) 1 else 0,
                learningIds = if (known) it.learningIds else it.learningIds + card.id,
            )
        }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
        buildDeck()
    }

    fun toggleSide() {
        _state.update { it.copy(termFirst = !it.termFirst) }
    }

    fun toggleOnlyFavorites() {
        _state.update { it.copy(onlyFavorites = !it.onlyFavorites) }
        buildDeck()
    }

    fun restart() = buildDeck()

    fun restartLearning() = buildDeck(only = _state.value.learningIds.toSet())

    fun toggleFavorite(card: CardEntity) {
        val updated = card.copy(favorite = !card.favorite)
        viewModelScope.launch { repository.setFavorite(card.id, updated.favorite) }
        allCards = allCards.map { if (it.id == card.id) it.copy(favorite = updated.favorite) else it }
        _state.update { ui ->
            ui.copy(
                deck = ui.deck.map { if (it.id == card.id) it.copy(favorite = updated.favorite) else it },
                hasFavorites = allCards.any { it.favorite },
            )
        }
    }
}

@Composable
fun FlashcardsScreen(nav: NavController, viewModel: FlashcardsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tts = rememberTts()

    Scaffold(
        topBar = {
            AppTopBar(
                title = state.setName.ifEmpty { "Карточки" },
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = viewModel::toggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Перемешать",
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = viewModel::toggleSide) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Поменять сторону")
                    }
                    if (state.hasFavorites) {
                        IconButton(onClick = viewModel::toggleOnlyFavorites) {
                            Icon(
                                if (state.onlyFavorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Только избранные",
                                tint = if (state.onlyFavorites) StatusColors.warning else LocalContentColor.current,
                            )
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
                .padding(16.dp),
        ) {
            val card = state.current
            when {
                state.loading -> Unit

                state.deck.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Style,
                    title = "Нет карточек",
                    text = if (state.onlyFavorites) "В наборе нет избранных карточек." else "Добавьте карточки в набор.",
                )

                state.finished || card == null -> FinishedDeck(
                    known = state.known,
                    learning = state.learningIds.size,
                    onRestartLearning = viewModel::restartLearning,
                    onRestart = viewModel::restart,
                    onClose = { nav.popBackStack() },
                )

                else -> {
                    Text(
                        "${state.index + 1} / ${state.deck.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.index.toFloat() / state.deck.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(16.dp))

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        // key: для каждой новой карточки состояние переворота и сдвига создаётся заново
                        key(card.id, state.termFirst) {
                            SwipeableCard(
                                card = card,
                                termFirst = state.termFirst,
                                image = card.imagePath?.let(viewModel::imageFile),
                                onSwiped = viewModel::swipe,
                                onSpeak = { showingTerm ->
                                    if (showingTerm) {
                                        tts.speak(card.term, state.termLang)
                                    } else {
                                        tts.speak(card.definition, state.defLang)
                                    }
                                },
                                onFavorite = { viewModel.toggleFavorite(card) },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { viewModel.swipe(false) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = StatusColors.wrong)
                            Spacer(Modifier.width(6.dp))
                            Text("Ещё учу")
                        }
                        Button(onClick = { viewModel.swipe(true) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Знаю")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Тап — перевернуть · свайп вправо — знаю · влево — ещё учу",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableCard(
    card: CardEntity,
    termFirst: Boolean,
    image: File?,
    onSwiped: (known: Boolean) -> Unit,
    onSpeak: (showingTerm: Boolean) -> Unit,
    onFavorite: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "flip",
    )
    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    val flyAway = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() } * 1.3f
    val currentOnSwiped by rememberUpdatedState(onSwiped)

    val showingBack = rotation > 90f
    val showingTerm = termFirst != showingBack

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = offsetX.value / 45f }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                offsetX.value > threshold -> {
                                    offsetX.animateTo(flyAway, tween(180))
                                    currentOnSwiped(true)
                                }
                                offsetX.value < -threshold -> {
                                    offsetX.animateTo(-flyAway, tween(180))
                                    currentOnSwiped(false)
                                }
                                else -> offsetX.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { flipped = !flipped })
            },
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 14f * this.density
                },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            // обратная сторона рисуется зеркально, поэтому разворачиваем её содержимое обратно
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = if (showingBack) 180f else 0f },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (showingTerm) "Термин" else "Перевод",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!showingTerm && image != null) {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        if (showingTerm) card.term else card.definition,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    if (showingTerm && card.example.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            card.example,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(onClick = { onSpeak(showingTerm) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить")
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(
                            if (card.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Избранное",
                            tint = if (card.favorite) StatusColors.warning else LocalContentColor.current,
                        )
                    }
                }

                // подсказка во время свайпа
                val drag = offsetX.value
                if (abs(drag) > threshold / 3) {
                    val known = drag > 0
                    Surface(
                        color = if (known) StatusColors.correct else StatusColors.wrong,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            if (known) "Знаю" else "Ещё учу",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FinishedDeck(
    known: Int,
    learning: Int,
    onRestartLearning: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = StatusColors.correct,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Колода пройдена", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Знаю: $known · Ещё учу: $learning", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (learning > 0) {
        Button(onClick = onRestartLearning, modifier = Modifier.fillMaxWidth()) {
            Text("Повторить «ещё учу» ($learning)")
        }
        Spacer(Modifier.height(8.dp))
    }
    FilledTonalButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Пройти заново") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("К набору") }
}
