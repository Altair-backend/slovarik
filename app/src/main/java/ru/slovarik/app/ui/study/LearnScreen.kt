package ru.slovarik.app.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.domain.AnswerChecker
import ru.slovarik.app.domain.Sm2
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.theme.StatusColors
import ru.slovarik.app.util.rememberTts
import javax.inject.Inject

data class LearnQuestion(
    /** Порядковый номер вопроса: отличает повторный вопрос по той же карточке. */
    val seq: Int,
    val cardId: Long,
    /** true — ввод с клавиатуры, false — выбор из вариантов. */
    val written: Boolean,
    val prompt: String,
    val answer: String,
    val options: List<String>,
    val promptLang: String,
)

sealed interface LearnFeedback {
    data class Correct(val userAnswer: String) : LearnFeedback
    data class Wrong(val userAnswer: String, val correctAnswer: String) : LearnFeedback

    /** Ответ отличается на одну букву — спрашиваем, засчитать ли. */
    data class Typo(val userAnswer: String, val correctAnswer: String) : LearnFeedback
}

data class LearnUi(
    val loading: Boolean = true,
    val empty: Boolean = false,
    /** Режим «повторить всё, что пора» (без привязки к набору). */
    val dueMode: Boolean = false,
    val total: Int = 0,
    val mastered: Int = 0,
    val progress: Float = 0f,
    val round: Int = 0,
    val question: LearnQuestion? = null,
    val feedback: LearnFeedback? = null,
    val roundFinished: Boolean = false,
    val finished: Boolean = false,
    val roundCorrect: Int = 0,
    val roundWrong: Int = 0,
)

/**
 * Адаптивное заучивание.
 *
 * Каждая карточка проходит два шага: сначала выбор из 4 вариантов (термин → перевод),
 * затем ввод термина с клавиатуры по переводу. Слова идут раундами по 7 штук.
 * Ошибочное слово возвращается в конец текущего раунда и в следующих раундах идёт первым.
 * Когда оба шага пройдены, результат записывается в SM-2 — и слово придёт на повторение позже.
 */
@HiltViewModel
class LearnViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private class Item(val card: CardEntity) {
        var stage = 0 // 0 — нужен выбор варианта, 1 — нужен ввод, 2 — выучено в этой сессии
        var mistakes = 0
        var failRecorded = false
    }

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L
    private val dueMode = setId == Routes.DUE_SET_ID

    private val items = mutableListOf<Item>()
    private val queue = ArrayDeque<Item>()
    private var current: Item? = null
    private var distractorPool: List<CardEntity> = emptyList()
    private var languages: Map<Long, Pair<String, String>> = emptyMap()
    private var seq = 0
    private var round = 0
    private var roundCorrect = 0
    private var roundWrong = 0

    private val _state = MutableStateFlow(LearnUi(dueMode = dueMode))
    val state: StateFlow<LearnUi> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val cards = if (dueMode) repository.getDueCards() else repository.getCards(setId)
            distractorPool = if (dueMode) repository.getAllCards() else cards
            languages = repository.getSets().associate { it.id to (it.termLang to it.defLang) }

            items.clear()
            round = 0
            if (cards.isEmpty()) {
                _state.value = LearnUi(loading = false, empty = true, dueMode = dueMode)
                return@launch
            }
            // сначала самые слабые слова
            items += cards.sortedWith(compareBy<CardEntity>({ it.level }, { it.dueAt })).map { Item(it) }
            startRound()
        }
    }

    fun restart() {
        _state.value = LearnUi(dueMode = dueMode)
        load()
    }

    fun startRound() {
        queue.clear()
        queue.addAll(
            items.filter { it.stage < 2 }
                .sortedByDescending { it.mistakes }
                .take(ROUND_SIZE)
                .shuffled(),
        )
        round++
        roundCorrect = 0
        roundWrong = 0
        showNext()
    }

    private fun showNext() {
        val item = queue.removeFirstOrNull()
        current = item
        val mastered = items.count { it.stage >= 2 }
        val progress = if (items.isEmpty()) 0f else items.sumOf { it.stage }.toFloat() / (items.size * 2)
        if (item == null) {
            val done = mastered == items.size
            _state.update {
                it.copy(
                    loading = false,
                    question = null,
                    feedback = null,
                    roundFinished = !done,
                    finished = done,
                    total = items.size,
                    mastered = mastered,
                    progress = progress,
                    round = round,
                    roundCorrect = roundCorrect,
                    roundWrong = roundWrong,
                )
            }
        } else {
            _state.update {
                it.copy(
                    loading = false,
                    question = buildQuestion(item),
                    feedback = null,
                    roundFinished = false,
                    finished = false,
                    total = items.size,
                    mastered = mastered,
                    progress = progress,
                    round = round,
                )
            }
        }
    }

    private fun buildQuestion(item: Item): LearnQuestion {
        val card = item.card
        val (termLang, defLang) = languages[card.setId] ?: ("en" to "ru")
        val distractors = distractorPool.asSequence()
            .filter { it.id != card.id }
            .map { it.definition }
            .filter { !it.equals(card.definition, ignoreCase = true) }
            .distinct()
            .toList()
            .shuffled()
            .take(3)

        // если вариантов для выбора не набирается (в наборе одно слово) — сразу просим ввести ответ
        val written = item.stage >= 1 || distractors.isEmpty()
        seq++
        return if (written) {
            LearnQuestion(seq, card.id, true, card.definition, card.term, emptyList(), defLang)
        } else {
            LearnQuestion(seq, card.id, false, card.term, card.definition, (distractors + card.definition).shuffled(), termLang)
        }
    }

    fun answerChoice(option: String) {
        val question = _state.value.question ?: return
        if (_state.value.feedback != null) return
        applyResult(option == question.answer, option)
    }

    fun answerWritten(text: String) {
        val question = _state.value.question ?: return
        if (_state.value.feedback != null) return
        when (AnswerChecker.check(text, question.answer)) {
            AnswerChecker.Verdict.CORRECT -> applyResult(true, text)
            AnswerChecker.Verdict.WRONG -> applyResult(false, text)
            AnswerChecker.Verdict.TYPO ->
                _state.update { it.copy(feedback = LearnFeedback.Typo(text.trim(), question.answer)) }
        }
    }

    /** Ответ на вопрос «засчитать опечатку?». */
    fun resolveTypo(accept: Boolean) {
        val typo = _state.value.feedback as? LearnFeedback.Typo ?: return
        _state.update { it.copy(feedback = null) }
        applyResult(accept, typo.userAnswer)
    }

    fun dontKnow() {
        if (_state.value.feedback != null) return
        applyResult(false, "")
    }

    private fun applyResult(correct: Boolean, userAnswer: String) {
        val item = current ?: return
        val question = _state.value.question ?: return
        if (correct) {
            roundCorrect++
            // в режиме ввода «с первого раза» шаг выбора не нужен — слово сразу считается выученным
            item.stage = if (question.written) 2 else item.stage + 1
            if (item.stage >= 2) {
                val quality = if (item.mistakes == 0) Sm2.QUALITY_PERFECT else Sm2.QUALITY_HARD
                viewModelScope.launch { repository.review(item.card.id, quality) }
            }
            _state.update { it.copy(feedback = LearnFeedback.Correct(userAnswer)) }
        } else {
            roundWrong++
            item.mistakes++
            queue.addLast(item) // ошибочное слово ещё раз вернётся в этом же раунде
            if (!item.failRecorded) {
                item.failRecorded = true
                viewModelScope.launch { repository.review(item.card.id, Sm2.QUALITY_WRONG) }
            }
            _state.update { it.copy(feedback = LearnFeedback.Wrong(userAnswer, question.answer)) }
        }
    }

    /** Переход к следующему вопросу после показа результата. */
    fun next() {
        val feedback = _state.value.feedback
        if (feedback == null || feedback is LearnFeedback.Typo) return
        showNext()
    }

    companion object {
        private const val ROUND_SIZE = 7
    }
}

@Composable
fun LearnScreen(nav: NavController, viewModel: LearnViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tts = rememberTts()

    // после верного ответа переходим дальше сами, после ошибки ждём кнопку «Далее»
    val question = state.question
    val feedback = state.feedback
    LaunchedEffect(question?.seq, feedback) {
        if (feedback is LearnFeedback.Correct) {
            delay(750)
            viewModel.next()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (state.dueMode) "Повторение" else "Заучивание",
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
        ) {
            when {
                state.loading -> Unit

                state.empty -> EmptyState(
                    icon = Icons.Filled.School,
                    title = if (state.dueMode) "Сейчас нечего повторять" else "В наборе нет карточек",
                    text = if (state.dueMode) {
                        "Слова появятся здесь, когда придёт время их вспомнить."
                    } else {
                        "Добавьте карточки, чтобы начать заучивание."
                    },
                ) {
                    Button(onClick = { nav.popBackStack() }) { Text("Назад") }
                }

                state.finished -> SummaryBlock(
                    title = "Все слова выучены!",
                    text = "Выучено слов: ${state.total}. Они вернутся на повторение через день и дальше — всё реже.",
                    primaryText = "Готово",
                    onPrimary = { nav.popBackStack() },
                    secondaryText = "Пройти ещё раз",
                    onSecondary = viewModel::restart,
                )

                state.roundFinished -> SummaryBlock(
                    title = "Раунд ${state.round} пройден",
                    text = "Верно: ${state.roundCorrect} · Ошибок: ${state.roundWrong}\n" +
                        "Выучено ${state.mastered} из ${state.total}",
                    primaryText = "Следующий раунд",
                    onPrimary = viewModel::startRound,
                    secondaryText = "Закончить",
                    onSecondary = { nav.popBackStack() },
                )

                question != null -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Раунд ${state.round} · выучено ${state.mastered} из ${state.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))

                    PromptCard(
                        label = if (question.written) "Введите термин" else "Выберите перевод",
                        text = question.prompt,
                        onSpeak = { tts.speak(question.prompt, question.promptLang) },
                    )
                    Spacer(Modifier.height(16.dp))

                    if (question.written) {
                        WrittenAnswer(
                            question = question,
                            feedback = feedback,
                            onSubmit = viewModel::answerWritten,
                            onDontKnow = viewModel::dontKnow,
                        )
                    } else {
                        question.options.forEach { option ->
                            OptionButton(
                                text = option,
                                state = optionState(option, question.answer, feedback),
                                enabled = feedback == null,
                                onClick = { viewModel.answerChoice(option) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (feedback == null) {
                            TextButton(onClick = viewModel::dontKnow, modifier = Modifier.fillMaxWidth()) {
                                Text("Не знаю")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    FeedbackBlock(
                        feedback = feedback,
                        onNext = viewModel::next,
                        onResolveTypo = viewModel::resolveTypo,
                    )
                }
            }
        }
    }
}

enum class OptionState { NEUTRAL, CORRECT, WRONG }

/** Как подсветить вариант после ответа: верный — зелёным, выбранный неверный — красным. */
private fun optionState(option: String, answer: String, feedback: LearnFeedback?): OptionState = when (feedback) {
    null, is LearnFeedback.Typo -> OptionState.NEUTRAL
    is LearnFeedback.Correct -> if (option == answer) OptionState.CORRECT else OptionState.NEUTRAL
    is LearnFeedback.Wrong -> when (option) {
        answer -> OptionState.CORRECT
        feedback.userAnswer -> OptionState.WRONG
        else -> OptionState.NEUTRAL
    }
}

@Composable
fun PromptCard(label: String, text: String, onSpeak: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            if (onSpeak != null) {
                IconButton(onClick = onSpeak, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить")
                }
            }
        }
    }
}

@Composable
fun OptionButton(text: String, state: OptionState, enabled: Boolean, onClick: () -> Unit) {
    val accent = when (state) {
        OptionState.CORRECT -> StatusColors.correct
        OptionState.WRONG -> StatusColors.wrong
        OptionState.NEUTRAL -> MaterialTheme.colorScheme.outlineVariant
    }
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (state == OptionState.NEUTRAL) 1.dp else 2.dp, accent),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (state == OptionState.NEUTRAL) Color.Transparent else accent.copy(alpha = 0.12f),
            disabledContainerColor = if (state == OptionState.NEUTRAL) Color.Transparent else accent.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            when (state) {
                OptionState.CORRECT -> Icon(Icons.Filled.Check, contentDescription = "Верно", tint = accent)
                OptionState.WRONG -> Icon(Icons.Filled.Close, contentDescription = "Неверно", tint = accent)
                OptionState.NEUTRAL -> Unit
            }
        }
    }
}

@Composable
private fun WrittenAnswer(
    question: LearnQuestion,
    feedback: LearnFeedback?,
    onSubmit: (String) -> Unit,
    onDontKnow: () -> Unit,
) {
    var input by remember(question.seq) { mutableStateOf("") }
    val locked = feedback != null

    OutlinedTextField(
        value = input,
        onValueChange = { if (!locked) input = it },
        label = { Text("Ваш ответ") },
        singleLine = true,
        readOnly = locked,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { if (!locked && input.isNotBlank()) onSubmit(input) }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (!locked) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDontKnow, modifier = Modifier.weight(1f)) { Text("Не знаю") }
            Button(onClick = { onSubmit(input) }, enabled = input.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Проверить")
            }
        }
    }
}

@Composable
private fun FeedbackBlock(
    feedback: LearnFeedback?,
    onNext: () -> Unit,
    onResolveTypo: (Boolean) -> Unit,
) {
    when (feedback) {
        null -> Unit

        is LearnFeedback.Correct -> Text(
            "Верно!",
            color = StatusColors.correct,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )

        is LearnFeedback.Wrong -> Column {
            Text(
                if (feedback.userAnswer.isBlank()) "Запомните правильный ответ" else "Неверно",
                color = StatusColors.wrong,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text("Правильный ответ: ${feedback.correctAnswer}")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Далее") }
        }

        is LearnFeedback.Typo -> Column {
            Text(
                "Похоже на опечатку",
                color = StatusColors.warning,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text("Вы ввели: ${feedback.userAnswer}\nПравильно: ${feedback.correctAnswer}")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onResolveTypo(false) }, modifier = Modifier.weight(1f)) {
                    Text("Не засчитывать")
                }
                Button(onClick = { onResolveTypo(true) }, modifier = Modifier.weight(1f)) {
                    Text("Засчитать")
                }
            }
        }
    }
}

@Composable
fun SummaryBlock(
    title: String,
    text: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = StatusColors.correct,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryText) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryText) }
    }
}
