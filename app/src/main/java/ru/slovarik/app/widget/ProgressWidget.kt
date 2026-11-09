package ru.slovarik.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.combine
import ru.slovarik.app.MainActivity
import ru.slovarik.app.di.appEntryPoint
import ru.slovarik.app.domain.Streak
import ru.slovarik.app.util.Time
import ru.slovarik.app.util.plural

/** Виджет «Прогресс»: сколько слов повторить сегодня и текущая серия дней. */
class ProgressWidget : GlanceAppWidget() {

    private data class Stats(val due: Int, val streak: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = context.appEntryPoint().repository()
        val initial = Stats(repository.dueCountToday(), repository.currentStreak())
        // Поток из базы: пока виджет «жив», он сам обновляется при изменении данных.
        val stats = combine(repository.observeDueCount(), repository.observeLog()) { due, log ->
            val days = log.filter { it.reviews > 0 }.map { it.day }.toSet()
            Stats(due, Streak.current(days, Time.today()))
        }

        provideContent {
            val current by stats.collectAsState(initial = initial)
            GlanceTheme {
                Content(context, current)
            }
        }
    }

    @Composable
    private fun Content(context: Context, stats: Stats) {
        val openApp = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .clickable(actionStartActivity(openApp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = stats.due.toString(),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = "${plural(stats.due, "слово", "слова", "слов")} повторить",
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "🔥 ${stats.streak}",
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = "${plural(stats.streak, "день", "дня", "дней")} подряд",
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
        }
    }
}

class ProgressWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProgressWidget()
}
