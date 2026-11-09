package ru.slovarik.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ru.slovarik.app.MainActivity
import ru.slovarik.app.data.Repository
import ru.slovarik.app.di.appEntryPoint

/**
 * Виджет «Слово дня». Всё, что он показывает, хранится в его собственном состоянии
 * (Preferences): источник слов, текущее слово и признак «перевод открыт».
 * Кнопки меняют это состояние и просят Glance перерисовать виджет.
 */
class WordWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    // Один виджет, две раскладки: маленькая (2×2) и средняя (4×2) — меняется при растягивании.
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WordWidgetContent()
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)

        val SOURCE_TYPE = stringPreferencesKey("source_type")
        val SOURCE_ID = longPreferencesKey("source_id")
        val SOURCE_NAME = stringPreferencesKey("source_name")
        val CARD_ID = longPreferencesKey("card_id")
        val SET_ID = longPreferencesKey("set_id")
        val TERM = stringPreferencesKey("term")
        val DEFINITION = stringPreferencesKey("definition")
        val REVEALED = booleanPreferencesKey("revealed")

        /** Выбирает новое случайное слово для виджета и перерисовывает его. */
        suspend fun showNextWord(context: Context, glanceId: GlanceId) {
            val repository = context.appEntryPoint().repository()
            updateAppWidgetState(context, glanceId) { prefs ->
                val card = repository.randomCard(
                    sourceType = prefs[SOURCE_TYPE] ?: Repository.SOURCE_ALL,
                    sourceId = prefs[SOURCE_ID] ?: 0L,
                    excludeId = prefs[CARD_ID],
                )
                if (card == null) {
                    prefs.remove(CARD_ID)
                    prefs.remove(SET_ID)
                    prefs[TERM] = ""
                    prefs[DEFINITION] = ""
                } else {
                    prefs[CARD_ID] = card.id
                    prefs[SET_ID] = card.setId
                    prefs[TERM] = card.term
                    prefs[DEFINITION] = card.definition
                }
                prefs[REVEALED] = false
            }
            WordWidget().update(context, glanceId)
        }
    }
}

class WordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WordWidget()
}

/** Кнопка «следующее слово». */
class NextWordAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WordWidget.showNextWord(context, glanceId)
    }
}

/** Кнопка «показать/скрыть перевод». */
class ToggleTranslationAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WordWidget.REVEALED] = !(prefs[WordWidget.REVEALED] ?: false)
        }
        WordWidget().update(context, glanceId)
    }
}

@Composable
private fun WordWidgetContent() {
    val context = LocalContext.current
    val prefs = currentState<Preferences>()
    val term = prefs[WordWidget.TERM].orEmpty()
    val definition = prefs[WordWidget.DEFINITION].orEmpty()
    val revealed = prefs[WordWidget.REVEALED] ?: false
    val cardId = prefs[WordWidget.CARD_ID]
    val setId = prefs[WordWidget.SET_ID]
    val sourceName = prefs[WordWidget.SOURCE_NAME] ?: "Слово дня"
    val medium = LocalSize.current.width >= WordWidget.MEDIUM.width

    // тап по слову открывает приложение на этой карточке
    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (cardId != null && setId != null) {
            action = Intent.ACTION_VIEW
            data = Uri.parse("slovarik://card/$setId/$cardId")
        }
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(12.dp),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .clickable(actionStartActivity(openIntent)),
        ) {
            Text(
                text = sourceName,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(4.dp))
            if (term.isEmpty()) {
                Text(
                    text = "Здесь пока нет слов. Добавьте карточки в приложении.",
                    maxLines = 3,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            } else {
                Text(
                    text = term,
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = if (medium) 24.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = if (revealed) definition else "• • •",
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = if (medium) 16.sp else 13.sp,
                    ),
                )
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetButton(
                text = when {
                    !medium -> if (revealed) "Скрыть" else "Перевод"
                    revealed -> "Скрыть перевод"
                    else -> "Показать перевод"
                },
                action = actionRunCallback<ToggleTranslationAction>(),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            WidgetButton(
                text = if (medium) "Следующее слово" else "Далее",
                action = actionRunCallback<NextWordAction>(),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

@Composable
private fun WidgetButton(text: String, action: Action, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier = modifier
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(14.dp)
            .clickable(action)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
