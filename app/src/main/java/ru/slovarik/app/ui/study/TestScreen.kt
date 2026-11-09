package ru.slovarik.app.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject
import kotlin.math.roundToInt

enum class TestType(val title: String) {
    CHOICE("Выбор варианта"),
    TRUE_FALSE("Верно / неверно"),
    WRITTEN("Ввод ответа"),
    MATCHING("Сопоставление"),
}

sealed interface TestQuestion {
    data class Choice(
        val card: CardEntity,
        val prompt: String,
        val answer: String,
        val options: List<String>,
    ) : TestQuestion

    data class TrueFalse(
        val card: CardEntity,
        val prompt: String,
        /** Показанный вариант: может быть верным или чужим. */
        val shown: String,
        val answer: String,
    ) : TestQuestion {
        val isTrue: Boolean get() = shown == answer
    }

    data class Written(val card: CardEntity, val prompt: String, val answer: String) : TestQuestion

    /** Несколько пар сразу: prompts[i] соответствует answers[i], shuffled — ответы вперемешку. */
    data class Matching(
        val cards: List<CardEntity>,
        val prompts: List<String>,
        val answers: List<String>,
        val shuffled: List<String>,
    ) : TestQuestion
}

/** Ответ пользователя на один вопрос (заполняется то поле, которое подходит типу вопроса). */
data class TestAnswer(
    val text: String = "",
    val bool: Boolean? = null,
    /** Для сопоставления: индекс слева -> индекс в shuffled. */
    val pairs: Map<Int, Int> = emptyMap(),
)

data class TestResultItem(
    val card: CardEntity,
    val prompt: String,
    val correctAnswer: String,
    val userAnswer: String,
    val correct: Boolean,
    /** Ответ отличается на одну букву — можно засчитать вручную. */
    val typo: Boolean = false,
)

enum class TestPhase { SETUP, RUNNING, RESULT }

data class TestUi(
    val loading: Boolean = true,
    val phase: TestPhase = TestPhase.SETUP,
    val available: Int = 0,
    val count: Int = 0,
    val types: Set<TestType> = setOf(TestType.CHOICE, TestType.TRUE_FALSE, TestType.WRITTEN, TestType.MATCHING),
    /** true — отвечаем переводом на термин, false — термином на перевод. */
    val answerWithDefinition: Boolean = true,
    val questions: List<TestQuestion> = emptyList(),
    val index: Int = 0,
    val answers: Map<Int, TestAnswer> = emptyMap(),
    val results: List<TestResultItem> = emptyList(),
) {
    val percent: Int
        get() = if (results.isEmpty()) 0 else (results.count { it.correct } * 100f / results.size).roundToInt()
}

@HiltViewModel
class TestViewModel @Inject constructor(
    private val repository: Repository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val setId: Long = savedStateHandle.get<Long>(Routes.ARG_SET) ?: 0L
    private var allCards: List<CardEntity> = emptyList()

    private val _state = MutableStateFlow(TestUi())
    val state: StateFlow<TestUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            allCards = repository.getCards(setId)
            _state.update {
                it.copy(loading = false, available = allCards.size, count = minOf(20, allCards.size))
            }
        }
    }

    // ---------- настройка ----------

    fun setCount(count: Int) = _state.update { it.copy(count = count.coerceIn(1, maxOf(1, it.available))) }

    fun toggleType(type: TestType) = _state.update {
        val types = if (type in it.types) it.types - type else it.types + type
        if (types.isEmpty()) it else it.copy(types = types) // хотя бы один тип должен остаться
    }

    fun setDirection(answerWithDefinition: Boolean) =
        _state.update { it.copy(answerWithDefinition = answerWithDefinition) }

    fun start() {
        val config = _state.value
        generate(allCards.shuffled().take(config.count))
    }

    fun backToSetup() = _state.update {
        it.copy(phase = TestPhase.SETUP, questions = emptyList(), answers = emptyMap(), results = emptyList(), index = 0)
    }

    /** Новая контрольная только из слов, в которых были ошибки. */
    fun retryMistakes() {
        val wrong = _state.value.results.filter { !it.correct }.map { it.card }.distinctBy { it.id }
        if (wrong.isNotEmpty()) generate(wrong.shuffled())
    }

    // ---------- генерация вопросов ----------

    private fun generate(cards: List<CardEntity>) {
        val config = _state.value
        val types = TestType.entries.filter { it in config.types }
        val buckets = types.associateWith { mutableListOf<CardEntity>() }
        cards.forEachIndexed { index, card -> buckets.getValue(types[index % types.size]).add(card) }

        // для сопоставления нужно минимум две пары — иначе отдаём карточку другому типу
        val matching = buckets[TestType.MATCHING].orEmpty().toMutableList()
        val fallback = mutableListOf<CardEntity>()
        if (matching.size == 1) {
            fallback += matching
            matching.clear()
        }
        val fallbackType = types.firstOrNull { it != TestType.MATCHING } ?: TestType.WRITTEN

        fun prompt(card: CardEntity) = if (config.answerWithDefinition) card.term else card.definition
        fun answer(card: CardEntity) = if (config.answerWithDefinition) card.definition else card.term
        fun otherAnswers(card: CardEntity) = allCards.asSequence()
            .filter { it.id != card.id }
            .map { answer(it) }
            .filter { !it.equals(answer(card), ignoreCase = true) }
            .distinct()
            .toList()
            .shuffled()

        fun single(type: TestType, card: CardEntity): TestQuestion = when (type) {
            TestType.CHOICE -> {
                val others = otherAnswers(card).take(3)
                if (others.isEmpty()) {
                    TestQuestion.Written(card, prompt(card), answer(card))
                } else {
                    TestQuestion.Choice(card, prompt(card), answer(card), (others + answer(card)).shuffled())
                }
            }
            TestType.TRUE_FALSE -> {
                val other = otherAnswers(card).firstOrNull()
                val shown = if (other == null || listOf(true, false).random()) answer(card) else other
                TestQuestion.TrueFalse(card, prompt(card), shown, answer(card))
            }
            else -> TestQuestion.Written(card, prompt(card), answer(card))
        }

        val singles = buildList {
            for (type in types) {
                if (type == TestType.MATCHING) continue
                buckets.getValue(type).forEach { add(single(type, it)) }
            }
            fallback.forEach { add(single(fallbackType, it)) }
        }.shuffled()

        // пары группируем по 5; одиночный «хвост» присоединяем к предыдущей группе
        val groups = matching.chunked(5).toMutableList()
        if (groups.size > 1 && groups.last().size == 1) {
            val tail = groups.removeAt(groups.lastIndex)
            groups[groups.lastIndex] = groups.last() + tail
        }
        val matchingQuestions = groups.map { group ->
            TestQuestion.Matching(
                cards = group,
                prompts = group.map { prompt(it) },
                answers = group.map { answer(it) },
                shuffled = group.map { answer(it) }.shuffled(),
            )
        }

        _state.update {
            it.copy(
                phase = TestPhase.RUNNING,
                questions = singles + matchingQuestions,
                index = 0,
                answers = emptyMap(),
                results = emptyList(),
            )
        }
    }

    // ---------- прохождение ----------

    fun setAnswer(answer: TestAnswer) = _state.update { it.copy(answers = it.answers + (it.index to answer)) }

    fun previous() = _state.update { it.copy(index = maxOf(0, it.index - 1)) }

    fun next() {
        val current = _state.value
        if (current.index < current.questions.lastIndex) {
            _state.update { it.copy(index = it.index + 1) }
        } else {
            finish()
        }
    }

    private fun finish() {
        val current = _state.value
        val results = current.questions.flatMapIndexed { index, question ->
            grade(question, current.answers[index] ?: TestAnswer())
        }
        _state.update { it.copy(phase = TestPhase.RESULT, results = results) }
        viewModelScope.launch {
            results.forEach { result ->
                repository.review(result.card.id, if (result.correct) Sm2.QUALITY_GOOD else Sm2.QUALITY_WRONG)
            }
        }
    }

    private fun grade(question: TestQuestion, answer: TestAnswer): List<TestResultItem> = when (question) {
        is TestQuestion.Choice -> listOf(
            TestResultItem(
                card = question.card,
                prompt = question.prompt,
                correctAnswer = question.answer,
                userAnswer = answer.text,
                correct = answer.text == question.answer,
            ),
        )

        is TestQuestion.TrueFalse -> listOf(
            TestResultItem(
                card = question.card,
                prompt = "${question.prompt} = ${question.shown}?",
                correctAnswer = if (question.isTrue) "Верно" else "Неверно (правильно: ${question.answer})",
                userAnswer = when (answer.bool) {
                    true -> "Верно"
                    false -> "Неверно"
                    null -> ""
                },
                correct = answer.bool == question.isTrue,
            ),
        )

        is TestQuestion.Written -> {
            val verdict = AnswerChecker.check(answer.text, question.answer)
            listOf(
                TestResultItem(
                    card = question.card,
                    prompt = question.prompt,
                    correctAnswer = question.answer,
                    userAnswer = answer.text.trim(),
                    correct = verdict == AnswerChecker.Verdict.CORRECT,
                    typo = verdict == AnswerChecker.Verdict.TYPO,
                ),
            )
        }

        is TestQuestion.Matching -> question.cards.mapIndexed { index, card ->
            val chosen = answer.pairs[index]?.let { question.shuffled.getOrNull(it) }.orEmpty()
            TestResultItem(
                card = card,
                prompt = question.prompts[index],
                correctAnswer = question.answers[index],
                userAnswer = chosen,
                correct = chosen == question.answers[index],
            )
        }
    }

    /** Засчитать ответ с опечаткой в одну букву. */
    fun acceptTypo(resultIndex: Int) {
        val item = _state.value.results.getOrNull(resultIndex) ?: return
        if (!item.typo || item.correct) return
        _state.update {
            it.copy(
                results = it.results.mapIndexed { index, result ->
                    if (index == resultIndex) result.copy(correct = true, typo = false) else result
                },
            )
        }
        viewModelScope.launch { repository.review(item.card.id, Sm2.QUALITY_GOOD) }
    }
}

@Composable
fun TestScreen(nav: NavController, viewModel: TestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppTopBar(title = "Контрольная", onBack = { nav.popBackStack() }) },
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
                state.available == 0 -> EmptyState(
                    icon = Icons.Filled.Quiz,
                    title = "В наборе нет карточек",
                    text = "Добавьте карточки, чтобы пройти контрольную.",
                )
                state.phase == TestPhase.SETUP -> TestSetup(state, viewModel)
                state.phase == TestPhase.RUNNING -> TestRunning(state, viewModel)
                else -> TestResults(state, viewModel, onClose = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun TestSetup(state: TestUi, viewModel: TestViewModel) {
    Text("Число вопросов: ${state.count}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (state.available > 1) {
        Slider(
            value = state.count.toFloat(),
            onValueChange = { viewModel.setCount(it.roundToInt()) },
            valueRange = 1f..state.available.toFloat(),
            steps = maxOf(0, state.available - 2),
        )
    }
    Spacer(Modifier.height(12.dp))

    Text("Типы вопросов", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TestType.entries.forEach { type ->
            FilterChip(
                selected = type in state.types,
                onClick = { viewModel.toggleType(type) },
                label = { Text(type.title) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    Text("Направление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.answerWithDefinition,
            onClick = { viewModel.setDirection(true) },
            label = { Text("Термин → перевод") },
        )
        FilterChip(
            selected = !state.answerWithDefinition,
            onClick = { viewModel.setDirection(false) },
            label = { Text("Перевод → термин") },
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) { Text("Начать контрольную") }
}

@Composable
private fun TestRunning(state: TestUi, viewModel: TestViewModel) {
    val question = state.questions.getOrNull(state.index) ?: return
    val answer = state.answers[state.index] ?: TestAnswer()
    val last = state.index == state.questions.lastIndex

    LinearProgressIndicator(
        progress = { (state.index + 1f) / state.questions.size },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Вопрос ${state.index + 1} из ${state.questions.size}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    when (question) {
        is TestQuestion.Choice -> {
            PromptCard(label = "Выберите ответ", text = question.prompt)
            Spacer(Modifier.height(16.dp))
            question.options.forEach { option ->
                SelectableOption(
                    text = option,
                    selected = answer.text == option,
                    onClick = { viewModel.setAnswer(TestAnswer(text = option)) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        is TestQuestion.TrueFalse -> {
            PromptCard(label = "Верно ли соответствие?", text = "${question.prompt}\n=\n${question.shown}")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    SelectableOption("Верно", answer.bool == true) { viewModel.setAnswer(TestAnswer(bool = true)) }
                }
                Box(Modifier.weight(1f)) {
                    SelectableOption("Неверно", answer.bool == false) { viewModel.setAnswer(TestAnswer(bool = false)) }
                }
            }
        }

        is TestQuestion.Written -> {
            PromptCard(label = "Введите ответ", text = question.prompt)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = answer.text,
                onValueChange = { viewModel.setAnswer(TestAnswer(text = it)) },
                label = { Text("Ваш ответ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is TestQuestion.Matching -> MatchingQuestion(
            question = question,
            pairs = answer.pairs,
            onChange = { viewModel.setAnswer(TestAnswer(pairs = it)) },
        )
    }

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = viewModel::previous,
            enabled = state.index > 0,
            modifier = Modifier.weight(1f),
        ) { Text("Назад") }
        Button(onClick = viewModel::next, modifier = Modifier.weight(1f)) {
            Text(if (last) "Завершить" else "Далее")
        }
    }
}

@Composable
private fun SelectableOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (selected) 2.dp else 1.dp, color),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Сопоставление: нажмите слово слева, затем его пару справа. Повторное нажатие снимает пару. */
@Composable
private fun MatchingQuestion(
    question: TestQuestion.Matching,
    pairs: Map<Int, Int>,
    onChange: (Map<Int, Int>) -> Unit,
) {
    var selectedLeft by remember(question) { mutableStateOf<Int?>(null) }

    Text(
        "Нажмите слово слева, затем его пару справа",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.prompts.forEachIndexed { index, prompt ->
                PairTile(
                    number = index + 1,
                    text = prompt,
                    highlighted = selectedLeft == index,
                    paired = index in pairs,
                    onClick = {
                        if (index in pairs) onChange(pairs - index)
                        selectedLeft = if (selectedLeft == index) null else index
                    },
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.shuffled.forEachIndexed { index, option ->
                val left = pairs.entries.firstOrNull { it.value == index }?.key
                PairTile(
                    number = left?.plus(1),
                    text = option,
                    highlighted = false,
                    paired = left != null,
                    onClick = {
                        val chosen = selectedLeft
                        if (chosen != null) {
                            // у одного ответа может быть только одна пара
                            onChange(pairs.filterValues { it != index } + (chosen to index))
                            selectedLeft = null
                        } else if (left != null) {
                            onChange(pairs - left)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PairTile(number: Int?, text: String, highlighted: Boolean, paired: Boolean, onClick: () -> Unit) {
    val border = when {
        highlighted -> MaterialTheme.colorScheme.primary
        paired -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (highlighted || paired) 2.dp else 1.dp, border),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (number != null) {
                Surface(color = border, shape = CircleShape, modifier = Modifier.size(22.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            number.toString(),
                            color = MaterialTheme.colorScheme.surface,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TestResults(state: TestUi, viewModel: TestViewModel, onClose: () -> Unit) {
    val correct = state.results.count { it.correct }
    val mistakes = state.results.size - correct

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${state.percent}%",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = if (state.percent >= 80) StatusColors.correct else MaterialTheme.colorScheme.primary,
        )
        Text(
            "Верно $correct из ${state.results.size}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(16.dp))

    if (mistakes > 0) {
        Button(onClick = viewModel::retryMistakes, modifier = Modifier.fillMaxWidth()) {
            Text("Повторить только ошибки ($mistakes)")
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::backToSetup, modifier = Modifier.weight(1f)) { Text("Новая контрольная") }
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Готово") }
    }
    Spacer(Modifier.height(20.dp))

    val sorted = state.results.withIndex().sortedBy { it.value.correct } // ошибки сверху
    if (mistakes > 0) {
        Text("Ошибки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
    }
    sorted.forEach { (index, result) ->
        if (index == sorted.firstOrNull { it.value.correct }?.index && mistakes > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Верные ответы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        ResultRow(result, onAcceptTypo = { viewModel.acceptTypo(index) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ResultRow(result: TestResultItem, onAcceptTypo: () -> Unit) {
    val color = if (result.correct) StatusColors.correct else StatusColors.wrong
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (result.correct) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = if (result.correct) "Верно" else "Ошибка",
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(result.prompt, fontWeight = FontWeight.SemiBold)
                if (!result.correct) {
                    Text(
                        "Ваш ответ: ${result.userAnswer.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.wrong,
                    )
                }
                Text(
                    "Правильно: ${result.correctAnswer}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.typo && !result.correct) {
                    TextButton(onClick = onAcceptTypo, contentPadding = PaddingValues(0.dp)) {
                        Text("Опечатка в одну букву — засчитать")
                    }
                }
            }
        }
    }
}
