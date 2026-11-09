package ru.slovarik.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetWithCounts
import ru.slovarik.app.util.cardsCount

/** Палитра и иконки папок. В базе хранится индекс. */
object FolderStyle {
    val colors = listOf(
        Color(0xFF4255FF),
        Color(0xFFE5484D),
        Color(0xFFF2890B),
        Color(0xFF2E9E5B),
        Color(0xFF0FA3B1),
        Color(0xFF8E4EC6),
        Color(0xFF7D5A44),
        Color(0xFF5B6B7A),
    )
    val icons: List<ImageVector> = listOf(
        Icons.Filled.Folder,
        Icons.Filled.School,
        Icons.Filled.Language,
        Icons.Filled.Work,
        Icons.Filled.Flight,
        Icons.Filled.Favorite,
        Icons.Filled.Science,
        Icons.Filled.MusicNote,
        Icons.Filled.Restaurant,
        Icons.Filled.Star,
    )

    fun color(index: Int): Color = colors[index.mod(colors.size)]
    fun icon(index: Int): ImageVector = icons[index.mod(icons.size)]
}

/** Языки для озвучки. */
object Languages {
    val all = listOf(
        "en" to "Английский",
        "ru" to "Русский",
        "de" to "Немецкий",
        "fr" to "Французский",
        "es" to "Испанский",
        "it" to "Итальянский",
        "pt" to "Португальский",
        "tr" to "Турецкий",
        "pl" to "Польский",
        "uk" to "Украинский",
        "zh" to "Китайский",
        "ja" to "Японский",
        "ko" to "Корейский",
    )

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: code
}

@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        },
        actions = { actions() },
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize())
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Карточка набора в списках. */
@Composable
fun SetCard(item: SetWithCounts, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                item.set.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${cardsCount(item.cardCount)} · выучено ${item.learnedCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Удалить",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Диалог создания и редактирования папки: название, цвет, иконка. */
@Composable
fun FolderDialog(
    initial: FolderEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int, icon: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableIntStateOf(initial?.color ?: 0) }
    var icon by remember { mutableIntStateOf(initial?.icon ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая папка" else "Изменить папку") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Цвет", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderStyle.colors.forEachIndexed { index, value ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(value)
                                .border(
                                    width = if (index == color) 3.dp else 0.dp,
                                    color = if (index == color) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { color = index },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Иконка", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderStyle.icons.forEachIndexed { index, value ->
                        val selected = index == icon
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) FolderStyle.color(color) else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { icon = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                value,
                                contentDescription = null,
                                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), color, icon) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Цветной кружок с иконкой папки. */
@Composable
fun FolderBadge(folder: FolderEntity, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FolderStyle.color(folder.color)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FolderStyle.icon(folder.icon), contentDescription = null, tint = Color.White)
    }
}

/** Простой выпадающий список «подпись: значение». */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.width(width.dp))

@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
