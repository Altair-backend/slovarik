package ru.slovarik.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.data.db.SetWithCounts
import ru.slovarik.app.domain.Streak
import ru.slovarik.app.ui.Routes
import ru.slovarik.app.ui.components.EmptyState
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.ui.components.SetCard
import ru.slovarik.app.util.Time
import ru.slovarik.app.util.cardsCount
import ru.slovarik.app.util.daysCount
import ru.slovarik.app.util.wordsCount
import javax.inject.Inject

data class HomeUi(
    val loading: Boolean = true,
    /** Набор, который учили последним (или самый новый, если ещё ничего не учили). */
    val continueSet: SetWithCounts? = null,
    val sets: List<SetWithCounts> = emptyList(),
    val due: List<CardEntity> = emptyList(),
    val streak: Int = 0,
    val todayReviews: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(repository: Repository) : ViewModel() {

    val state: StateFlow<HomeUi> = combine(
        repository.observeSets(),
        repository.observeDueCards(),
        repository.observeLog(),
    ) { sets, due, log ->
        val today = Time.today()
        val nonEmpty = sets.filter { it.cardCount > 0 }
        HomeUi(
            loading = false,
            continueSet = nonEmpty.filter { it.set.lastStudiedAt > 0 }.maxByOrNull { it.set.lastStudiedAt }
                ?: nonEmpty.firstOrNull(),
            sets = sets,
            due = due,
            streak = Streak.current(log.filter { it.reviews > 0 }.map { it.day }.toSet(), today),
            todayReviews = log.firstOrNull { it.day == today }?.reviews ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}

@Composable
fun HomeScreen(nav: NavController, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Словарик", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Поиск")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.setEdit()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Набор") },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StreakRow(state.streak, state.todayReviews) }

            state.continueSet?.let { set ->
                item {
                    ContinueCard(
                        item = set,
                        onOpen = { nav.navigate(Routes.set(set.set.id)) },
                        onLearn = { nav.navigate(Routes.learn(set.set.id)) },
                        onFlashcards = { nav.navigate(Routes.flashcards(set.set.id)) },
                    )
                }
            }

            item {
                DueCard(
                    due = state.due,
                    onReview = { nav.navigate(Routes.learn(Routes.DUE_SET_ID)) },
                )
            }

            item { SectionTitle("Мои наборы") }
            if (state.sets.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = "Пока нет наборов",
                        text = "Создайте первый набор и добавьте в него слова.",
                    )
                }
            } else {
                items(state.sets, key = { it.set.id }) { set ->
                    SetCard(set, onClick = { nav.navigate(Routes.set(set.set.id)) })
                }
            }
        }
    }
}

@Composable
private fun StreakRow(streak: Int, todayReviews: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallStat(
            icon = { Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
            value = daysCount(streak),
            label = "серия подряд",
            modifier = Modifier.weight(1f),
        )
        SmallStat(
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            value = todayReviews.toString(),
            label = "ответов сегодня",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SmallStat(icon: @Composable () -> Unit, value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ContinueCard(
    item: SetWithCounts,
    onOpen: () -> Unit,
    onLearn: () -> Unit,
    onFlashcards: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Продолжить обучение", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                item.set.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${cardsCount(item.cardCount)} · выучено ${item.learnedCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLearn, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Заучивание", maxLines = 1)
                }
                FilledTonalButton(onClick = onFlashcards, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Style, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Карточки", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DueCard(due: List<CardEntity>, onReview: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Пора повторить", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (due.isNotEmpty()) {
                    Text(
                        wordsCount(due.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (due.isEmpty()) {
                Text(
                    "На сегодня всё повторено. Слова появятся здесь, когда придёт время их вспомнить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                due.take(5).forEach { card ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            card.term,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            card.definition,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (due.size > 5) {
                    Text(
                        "и ещё ${due.size - 5}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                    Text("Повторить ${wordsCount(due.size)}")
                }
            }
        }
    }
}
