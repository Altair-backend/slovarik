package ru.slovarik.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.slovarik.app.data.db.AppDatabase
import ru.slovarik.app.data.db.CardDao
import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.data.db.FolderDao
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetDao
import ru.slovarik.app.data.db.SetEntity
import ru.slovarik.app.data.db.SetWithCounts
import ru.slovarik.app.data.db.StudyLogDao
import ru.slovarik.app.data.db.StudyLogEntity
import ru.slovarik.app.domain.Sm2
import ru.slovarik.app.domain.Streak
import ru.slovarik.app.util.Time
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Единая точка доступа к данным для ViewModel, виджетов и фоновых задач. */
@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val folderDao: FolderDao,
    private val setDao: SetDao,
    private val cardDao: CardDao,
    private val logDao: StudyLogDao,
) {
    // ---------- Папки ----------

    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()
    fun observeFolder(id: Long): Flow<FolderEntity?> = folderDao.observe(id)
    suspend fun getFolders(): List<FolderEntity> = folderDao.getAll()

    suspend fun saveFolder(folder: FolderEntity): Long =
        if (folder.id == 0L) {
            folderDao.insert(folder)
        } else {
            folderDao.update(folder)
            folder.id
        }

    /** Удаляет папку. Наборы из неё не удаляются, а остаются «без папки». */
    suspend fun deleteFolder(id: Long) = folderDao.deleteById(id)

    // ---------- Наборы ----------

    fun observeSets(): Flow<List<SetWithCounts>> = setDao.observeAllWithCounts()
    fun observeSet(id: Long): Flow<SetWithCounts?> = setDao.observeWithCounts(id)
    suspend fun getSet(id: Long): SetEntity? = setDao.get(id)
    suspend fun getSets(): List<SetEntity> = setDao.getAll()

    suspend fun saveSet(set: SetEntity): Long =
        if (set.id == 0L) {
            setDao.insert(set)
        } else {
            setDao.update(set)
            set.id
        }

    suspend fun moveSet(setId: Long, folderId: Long?) = setDao.move(setId, folderId)

    suspend fun deleteSet(id: Long) {
        val images = cardDao.getBySet(id).mapNotNull { it.imagePath }
        setDao.deleteById(id) // карточки удалятся каскадно
        images.forEach { deleteImage(it) }
    }

    suspend fun resetProgress(setId: Long) = cardDao.resetProgress(setId)

    // ---------- Карточки ----------

    fun observeCards(setId: Long): Flow<List<CardEntity>> = cardDao.observeBySet(setId)
    fun observeAllCards(): Flow<List<CardEntity>> = cardDao.observeAll()
    fun observeDueCards(): Flow<List<CardEntity>> = cardDao.observeDue(Time.endOfToday())
    suspend fun getCards(setId: Long): List<CardEntity> = cardDao.getBySet(setId)
    suspend fun getAllCards(): List<CardEntity> = cardDao.getAll()
    suspend fun getDueCards(): List<CardEntity> = cardDao.getDue(Time.endOfToday())
    suspend fun getCard(id: Long): CardEntity? = cardDao.get(id)

    suspend fun saveCard(card: CardEntity): Long =
        if (card.id == 0L) {
            cardDao.insert(card.copy(position = cardDao.maxPosition(card.setId) + 1))
        } else {
            cardDao.update(card)
            card.id
        }

    /** Массовое добавление пар «термин — перевод». Возвращает число добавленных карточек. */
    suspend fun addCards(setId: Long, pairs: List<Pair<String, String>>): Int {
        if (pairs.isEmpty()) return 0
        val start = cardDao.maxPosition(setId)
        cardDao.insertAll(
            pairs.mapIndexed { index, (term, definition) ->
                CardEntity(setId = setId, term = term, definition = definition, position = start + index + 1)
            },
        )
        return pairs.size
    }

    suspend fun deleteCard(card: CardEntity) {
        cardDao.deleteById(card.id)
        card.imagePath?.let { deleteImage(it) }
    }

    suspend fun setFavorite(cardId: Long, favorite: Boolean) = cardDao.setFavorite(cardId, favorite)

    // ---------- Обучение ----------

    /**
     * Записывает результат ответа на карточку: пересчитывает SM-2, уровень знания,
     * счётчики верных/неверных ответов и активность за сегодня.
     */
    suspend fun review(cardId: Long, quality: Int) {
        db.withTransaction {
            val card = cardDao.get(cardId) ?: return@withTransaction
            val now = System.currentTimeMillis()
            val correct = quality >= Sm2.QUALITY_HARD
            val result = Sm2.review(
                Sm2.State(card.easeFactor, card.intervalDays, card.repetitions),
                quality,
                now,
            )
            cardDao.update(
                card.copy(
                    correctCount = card.correctCount + if (correct) 1 else 0,
                    wrongCount = card.wrongCount + if (correct) 0 else 1,
                    lastReviewedAt = now,
                    level = Sm2.nextLevel(card.level, quality),
                    easeFactor = result.state.easeFactor,
                    intervalDays = result.state.intervalDays,
                    repetitions = result.state.repetitions,
                    dueAt = result.dueAt,
                ),
            )
            setDao.touch(card.setId, now)
            addToLog(1, if (correct) 1 else 0)
        }
    }

    /** Учитывает активность без изменения карточек (например, игра «Сопоставление»). */
    suspend fun logActivity(reviews: Int, correct: Int, setId: Long? = null) {
        db.withTransaction {
            addToLog(reviews, correct)
            if (setId != null) setDao.touch(setId, System.currentTimeMillis())
        }
    }

    private suspend fun addToLog(reviews: Int, correct: Int) {
        val day = Time.today()
        val current = logDao.get(day)
        logDao.upsert(
            StudyLogEntity(
                day = day,
                reviews = (current?.reviews ?: 0) + reviews,
                correct = (current?.correct ?: 0) + correct,
            ),
        )
    }

    // ---------- Статистика и виджеты ----------

    fun observeLog(): Flow<List<StudyLogEntity>> = logDao.observeAll()
    fun observeDueCount(): Flow<Int> = cardDao.observeDueCount(Time.endOfToday())
    suspend fun dueCountToday(): Int = cardDao.countDue(Time.endOfToday())

    suspend fun currentStreak(): Int {
        val days = logDao.getAll().filter { it.reviews > 0 }.map { it.day }.toSet()
        return Streak.current(days, Time.today())
    }

    /** Случайная карточка для виджета. sourceType: "all", "set" или "folder". */
    suspend fun randomCard(sourceType: String, sourceId: Long, excludeId: Long?): CardEntity? {
        val cards = when (sourceType) {
            SOURCE_SET -> cardDao.getBySet(sourceId)
            SOURCE_FOLDER -> cardDao.getByFolder(sourceId)
            else -> cardDao.getAll()
        }
        return cards.filter { it.id != excludeId }.ifEmpty { cards }.randomOrNull()
    }

    suspend fun isEmpty(): Boolean = setDao.getAll().isEmpty() && folderDao.getAll().isEmpty()

    // ---------- Картинки ----------

    val imagesDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    fun imageFile(name: String): File = File(imagesDir, name)

    /** Копирует выбранную в галерее картинку во внутреннюю память и возвращает имя файла. */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val name = "${UUID.randomUUID()}.jpg"
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                null
            } else {
                input.use { source ->
                    imageFile(name).outputStream().use { target -> source.copyTo(target) }
                }
                name
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteImage(name: String) = withContext(Dispatchers.IO) {
        runCatching { imageFile(name).delete() }
        Unit
    }

    // ---------- Полная замена данных (восстановление из копии) ----------

    suspend fun replaceAll(
        folders: List<FolderEntity>,
        sets: List<SetEntity>,
        cards: List<CardEntity>,
        logs: List<StudyLogEntity>,
    ) {
        db.withTransaction {
            logDao.clear()
            cardDao.clear()
            setDao.clear()
            folderDao.clear()
            folderDao.insertAll(folders)
            setDao.insertAll(sets)
            cardDao.insertAll(cards)
            logDao.upsertAll(logs)
        }
    }

    suspend fun snapshotLog(): List<StudyLogEntity> = logDao.getAll()

    /** Данные набора для первого запуска: ждём первое значение потока. */
    suspend fun firstSets(): List<SetWithCounts> = observeSets().first()

    companion object {
        const val SOURCE_ALL = "all"
        const val SOURCE_SET = "set"
        const val SOURCE_FOLDER = "folder"
    }
}
