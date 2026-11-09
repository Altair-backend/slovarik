package ru.slovarik.app.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetEntity
import ru.slovarik.app.data.db.StudyLogEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Экспорт наборов в текст/CSV и резервная копия всех данных.
 * Резервная копия — это zip-архив: data.json (папки, наборы, карточки, статистика) и папка images/.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: Repository,
) {
    // ---------- Экспорт набора ----------

    /** Текст: «термин — перевод» построчно. Такой файл можно импортировать обратно. */
    suspend fun exportSetAsText(setId: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val text = repository.getCards(setId).joinToString("\n") { "${it.term} — ${it.definition}" }
        write(uri, text)
    }

    /** CSV с колонками «термин, перевод, пример»; BOM в начале нужен, чтобы Excel понял UTF-8. */
    suspend fun exportSetAsCsv(setId: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val rows = buildString {
            append('﻿')
            append("term,definition,example\n")
            repository.getCards(setId).forEach { card ->
                append(csv(card.term)).append(',')
                append(csv(card.definition)).append(',')
                append(csv(card.example)).append('\n')
            }
        }
        write(uri, rows)
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun write(uri: Uri, text: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Не удалось открыть файл для записи")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    // ---------- Резервная копия ----------

    suspend fun createBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val folders = repository.getFolders()
        val sets = repository.getSets()
        val cards = repository.getAllCards()
        val logs = repository.snapshotLog()

        val json = JSONObject().apply {
            put("app", "slovarik")
            put("version", 1)
            put("createdAt", System.currentTimeMillis())
            put("folders", JSONArray(folders.map(::folderToJson)))
            put("sets", JSONArray(sets.map(::setToJson)))
            put("cards", JSONArray(cards.map(::cardToJson)))
            put("log", JSONArray(logs.map(::logToJson)))
        }

        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Не удалось открыть файл для записи")
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(json.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            cards.mapNotNull { it.imagePath }.distinct().forEach { name ->
                val file = repository.imageFile(name)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(IMAGES_PREFIX + name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Восстановление полностью заменяет текущие данные содержимым копии. */
    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        var jsonText: String? = null

        val stream = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name == DATA_ENTRY -> jsonText = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name.startsWith(IMAGES_PREFIX) -> {
                        // берём только имя файла, чтобы архив не мог записать что-то вне папки
                        val name = File(entry.name).name
                        File(tempDir, name).outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }
        }

        val text = jsonText ?: throw IllegalArgumentException("Это не резервная копия Словарика")
        val json = JSONObject(text)
        if (json.optString("app") != "slovarik") {
            throw IllegalArgumentException("Это не резервная копия Словарика")
        }

        val folders = json.getJSONArray("folders").objects().map(::folderFromJson)
        val sets = json.getJSONArray("sets").objects().map(::setFromJson)
        val cards = json.getJSONArray("cards").objects().map(::cardFromJson)
        val logs = json.optJSONArray("log")?.objects()?.map(::logFromJson).orEmpty()

        repository.replaceAll(folders, sets, cards, logs)

        // картинки: старые удаляем, из копии переносим на место
        val imagesDir = repository.imagesDir
        imagesDir.listFiles()?.forEach { it.delete() }
        tempDir.listFiles()?.forEach { it.copyTo(File(imagesDir, it.name), overwrite = true) }
        tempDir.deleteRecursively()
        Unit
    }

    // ---------- JSON ----------

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun folderToJson(f: FolderEntity) = JSONObject().apply {
        put("id", f.id)
        put("name", f.name)
        put("color", f.color)
        put("icon", f.icon)
        put("createdAt", f.createdAt)
    }

    private fun folderFromJson(o: JSONObject) = FolderEntity(
        id = o.getLong("id"),
        name = o.getString("name"),
        color = o.optInt("color"),
        icon = o.optInt("icon"),
        createdAt = o.optLong("createdAt"),
    )

    private fun setToJson(s: SetEntity) = JSONObject().apply {
        put("id", s.id)
        put("folderId", s.folderId ?: JSONObject.NULL)
        put("name", s.name)
        put("description", s.description)
        put("termLang", s.termLang)
        put("defLang", s.defLang)
        put("createdAt", s.createdAt)
        put("lastStudiedAt", s.lastStudiedAt)
    }

    private fun setFromJson(o: JSONObject) = SetEntity(
        id = o.getLong("id"),
        folderId = if (o.isNull("folderId")) null else o.getLong("folderId"),
        name = o.getString("name"),
        description = o.optString("description"),
        termLang = o.optString("termLang", "en"),
        defLang = o.optString("defLang", "ru"),
        createdAt = o.optLong("createdAt"),
        lastStudiedAt = o.optLong("lastStudiedAt"),
    )

    private fun cardToJson(c: CardEntity) = JSONObject().apply {
        put("id", c.id)
        put("setId", c.setId)
        put("term", c.term)
        put("definition", c.definition)
        put("example", c.example)
        put("imagePath", c.imagePath ?: JSONObject.NULL)
        put("favorite", c.favorite)
        put("position", c.position)
        put("correctCount", c.correctCount)
        put("wrongCount", c.wrongCount)
        put("lastReviewedAt", c.lastReviewedAt)
        put("level", c.level)
        put("easeFactor", c.easeFactor)
        put("intervalDays", c.intervalDays)
        put("repetitions", c.repetitions)
        put("dueAt", c.dueAt)
    }

    private fun cardFromJson(o: JSONObject) = CardEntity(
        id = o.getLong("id"),
        setId = o.getLong("setId"),
        term = o.getString("term"),
        definition = o.getString("definition"),
        example = o.optString("example"),
        imagePath = if (o.isNull("imagePath")) null else o.getString("imagePath"),
        favorite = o.optBoolean("favorite"),
        position = o.optInt("position"),
        correctCount = o.optInt("correctCount"),
        wrongCount = o.optInt("wrongCount"),
        lastReviewedAt = o.optLong("lastReviewedAt"),
        level = o.optInt("level"),
        easeFactor = o.optDouble("easeFactor", 2.5),
        intervalDays = o.optInt("intervalDays"),
        repetitions = o.optInt("repetitions"),
        dueAt = o.optLong("dueAt"),
    )

    private fun logToJson(l: StudyLogEntity) = JSONObject().apply {
        put("day", l.day)
        put("reviews", l.reviews)
        put("correct", l.correct)
    }

    private fun logFromJson(o: JSONObject) = StudyLogEntity(
        day = o.getLong("day"),
        reviews = o.optInt("reviews"),
        correct = o.optInt("correct"),
    )

    companion object {
        private const val DATA_ENTRY = "data.json"
        private const val IMAGES_PREFIX = "images/"
    }
}
