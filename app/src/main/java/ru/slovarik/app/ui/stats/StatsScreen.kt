package ru.slovarik.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.slovarik.app.data.Repository
import ru.slovarik.app.domain.Streak
import ru.slovarik.app.ui.components.AppTopBar
import ru.slovarik.app.ui.components.SectionTitle
import ru.slovarik.app.ui.theme.StatusColors
import ru.slovarik.app.util.Time
import ru.slovarik.app.util.daysCount
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

data class DayActivity(val label: String, val reviews: Int, val isToday: Boolean)

data class StatsUi(
    val loading: Boolean = true,
    val total: Int = 0,
    val learned: Int = 0,
    val learning: Int = 0,
    val fresh: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val todayReviews: Int = 0,
    val totalReviews: Int = 0,
    /** Доля верных ответов за всё время, 0..100. */
    val accuracy: Int = 0,
    val days: List<DayActivity> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(repository: Repository) : ViewModel() {

    val state: StateFlow<StatsUi> = combine(repository.observeAllCards(), repository.observeLog()) { cards, log ->
        val today = Time.today()
        val byDay = log.associateBy { it.day }
        val activeDays = log.filter { it.reviews > 0 }.map { it.day }.toSet()
        val totalReviews = log.sumOf { it.reviews }
        val totalCorrect = log.sumOf { it.correct }
        StatsUi(
            loading = false,
            total = cards.size,
            learned = cards.count { it.isLearned },
            learning = cards.count { !it.isNew && !it.isLearned },
            fresh = cards.count { it.isNew },
            streak = Streak.current(activeDays, today),
            bestStreak = Streak.best(activeDays),
            todayReviews = byDay[today]?.reviews ?: 0,
            totalReviews = totalReviews,
            accuracy = if (totalReviews == 0) 0 else (totalCorrect * 100f / totalReviews).roundToInt(),
            days = (DAYS_SHOWN - 1 downTo 0).map { back ->
                val day = today - back
                DayActivity(
                    label = LocalDate.ofEpochDay(day).dayOfMonth.toString(),
                    reviews = byDay[day]?.reviews ?: 0,
                    isToday = back == 0,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())

    companion object {
        const val DAYS_SHOWN = 14
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar(title = "Статистика") }) { padding ->
        if (state.loading) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    icon = Icons.Filled.LocalFireDepartment,
                    value = daysCount(state.streak),
                    label = "серия подряд",
                    hint = "рекорд: ${daysCount(state.bestStreak)}",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    icon = Icons.Filled.School,
                    value = state.learned.toString(),
                    label = "слов выучено",
                    hint = "из ${state.total}",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    icon = Icons.Filled.CheckCircle,
                    value = state.todayReviews.toString(),
                    label = "ответов сегодня",
                    hint = "всего: ${state.totalReviews}",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    icon = Icons.Filled.TrackChanges,
                    value = "${state.accuracy}%",
                    label = "верных ответов",
                    hint = "за всё время",
                    modifier = Modifier.weight(1f),
                )
            }

            SectionTitle("Активность за ${StatsViewModel.DAYS_SHOWN} дней")
            ActivityChart(state.days)

            SectionTitle("Слова по уровню знания")
            LevelBar(learned = state.learned, learning = state.learning, fresh = state.fresh)
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, hint: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Столбчатый график: один столбец — число ответов за день, подпись — число месяца. */
@Composable
private fun ActivityChart(days: List<DayActivity>) {
    val max = (days.maxOfOrNull { it.reviews } ?: 0).coerceAtLeast(1)
    val chartHeight = 140.dp

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            if (days.all { it.reviews == 0 }) {
                Text(
                    "Пока нет занятий. Пройдите любой режим — и здесь появится график.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (day.reviews > 0) day.reviews.toString() else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.height(chartHeight), contentAlignment = Alignment.BottomCenter) {
                            val fraction = day.reviews.toFloat() / max
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(if (day.reviews == 0) 3.dp else (chartHeight * fraction).coerceAtLeast(6.dp))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                    .background(
                                        when {
                                            day.reviews == 0 -> MaterialTheme.colorScheme.outlineVariant
                                            day.isToday -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                        },
                                    ),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            day.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelBar(learned: Int, learning: Int, fresh: Int) {
    val total = learned + learning + fresh
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            if (total > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp)),
                ) {
                    if (learned > 0) Box(Modifier.weight(learned.toFloat()).fillMaxHeight().background(StatusColors.correct))
                    if (learning > 0) Box(Modifier.weight(learning.toFloat()).fillMaxHeight().background(StatusColors.warning))
                    if (fresh > 0) Box(Modifier.weight(fresh.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                }
                Spacer(Modifier.height(12.dp))
            }
            Legend(StatusColors.correct, "Выучено", learned)
            Legend(StatusColors.warning, "Учу", learning)
            Legend(MaterialTheme.colorScheme.outlineVariant, "Новые", fresh)
        }
    }
}

@Composable
private fun Legend(color: Color, title: String, count: Int) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f))
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
    }
}
