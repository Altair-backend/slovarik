package ru.slovarik.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Папка: объединяет несколько наборов. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Индекс цвета в палитре FolderStyle.colors. */
    val color: Int = 0,
    /** Индекс иконки в списке FolderStyle.icons. */
    val icon: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Набор карточек. Может лежать в одной папке или быть без папки (folderId = null). */
@Entity(
    tableName = "sets",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val name: String,
    val description: String = "",
    /** Код языка термина (BCP-47), например "en". Нужен для озвучки. */
    val termLang: String = "en",
    /** Код языка перевода, например "ru". */
    val defLang: String = "ru",
    val createdAt: Long = System.currentTimeMillis(),
    val lastStudiedAt: Long = 0,
)

/** Карточка со статистикой и параметрами интервального повторения (SM-2). */
@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = SetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("setId"), Index("dueAt")],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    val term: String,
    val definition: String,
    val example: String = "",
    /** Имя файла картинки в папке files/images приложения. */
    val imagePath: String? = null,
    val favorite: Boolean = false,
    val position: Int = 0,
    // --- статистика ---
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    /** Время последнего повторения, 0 — карточку ещё не учили. */
    val lastReviewedAt: Long = 0,
    /** Уровень знания 0..5 (3 и выше считается «выучено»). */
    val level: Int = 0,
    // --- SM-2 ---
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    /** Когда карточку пора повторить (мс). */
    val dueAt: Long = 0,
) {
    val isNew: Boolean get() = lastReviewedAt == 0L
    val isLearned: Boolean get() = level >= LEARNED_LEVEL

    companion object {
        const val LEARNED_LEVEL = 3
        const val MAX_LEVEL = 5
    }
}

/** Активность за день: day — номер дня от начала эпохи (LocalDate.toEpochDay). */
@Entity(tableName = "study_log")
data class StudyLogEntity(
    @PrimaryKey val day: Long,
    val reviews: Int = 0,
    val correct: Int = 0,
)

/** Набор вместе со счётчиками карточек. */
data class SetWithCounts(
    @Embedded val set: SetEntity,
    val cardCount: Int,
    val learnedCount: Int,
) {
    val progress: Float get() = if (cardCount == 0) 0f else learnedCount.toFloat() / cardCount
}
