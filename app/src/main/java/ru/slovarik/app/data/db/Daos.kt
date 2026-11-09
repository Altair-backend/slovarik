package ru.slovarik.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observe(id: Long): Flow<FolderEntity?>

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Insert
    suspend fun insertAll(folders: List<FolderEntity>)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM folders")
    suspend fun clear()
}

private const val SET_WITH_COUNTS = """
    SELECT s.*,
        (SELECT COUNT(*) FROM cards c WHERE c.setId = s.id) AS cardCount,
        (SELECT COUNT(*) FROM cards c WHERE c.setId = s.id AND c.level >= 3) AS learnedCount
    FROM sets s
"""

@Dao
interface SetDao {
    @Query("$SET_WITH_COUNTS ORDER BY s.createdAt DESC")
    fun observeAllWithCounts(): Flow<List<SetWithCounts>>

    @Query("$SET_WITH_COUNTS WHERE s.id = :id")
    fun observeWithCounts(id: Long): Flow<SetWithCounts?>

    @Query("SELECT * FROM sets ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SetEntity>

    @Query("SELECT * FROM sets WHERE id = :id")
    suspend fun get(id: Long): SetEntity?

    @Insert
    suspend fun insert(set: SetEntity): Long

    @Insert
    suspend fun insertAll(sets: List<SetEntity>)

    @Update
    suspend fun update(set: SetEntity)

    @Query("UPDATE sets SET folderId = :folderId WHERE id = :setId")
    suspend fun move(setId: Long, folderId: Long?)

    @Query("UPDATE sets SET lastStudiedAt = :time WHERE id = :setId")
    suspend fun touch(setId: Long, time: Long)

    @Query("DELETE FROM sets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sets")
    suspend fun clear()
}

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE setId = :setId ORDER BY position, id")
    fun observeBySet(setId: Long): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE setId = :setId ORDER BY position, id")
    suspend fun getBySet(setId: Long): List<CardEntity>

    @Query("SELECT c.* FROM cards c INNER JOIN sets s ON s.id = c.setId WHERE s.folderId = :folderId")
    suspend fun getByFolder(folderId: Long): List<CardEntity>

    @Query("SELECT * FROM cards")
    fun observeAll(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun getAll(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun get(id: Long): CardEntity?

    @Query("SELECT * FROM cards WHERE lastReviewedAt > 0 AND dueAt <= :until ORDER BY dueAt")
    fun observeDue(until: Long): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE lastReviewedAt > 0 AND dueAt <= :until ORDER BY dueAt")
    suspend fun getDue(until: Long): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE lastReviewedAt > 0 AND dueAt <= :until")
    fun observeDueCount(until: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE lastReviewedAt > 0 AND dueAt <= :until")
    suspend fun countDue(until: Long): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM cards WHERE setId = :setId")
    suspend fun maxPosition(setId: Long): Int

    @Insert
    suspend fun insert(card: CardEntity): Long

    @Insert
    suspend fun insertAll(cards: List<CardEntity>)

    @Update
    suspend fun update(card: CardEntity)

    @Query("UPDATE cards SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query(
        "UPDATE cards SET correctCount = 0, wrongCount = 0, lastReviewedAt = 0, level = 0, " +
            "easeFactor = 2.5, intervalDays = 0, repetitions = 0, dueAt = 0 WHERE setId = :setId",
    )
    suspend fun resetProgress(setId: Long)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cards")
    suspend fun clear()
}

@Dao
interface StudyLogDao {
    @Query("SELECT * FROM study_log ORDER BY day")
    fun observeAll(): Flow<List<StudyLogEntity>>

    @Query("SELECT * FROM study_log ORDER BY day")
    suspend fun getAll(): List<StudyLogEntity>

    @Query("SELECT * FROM study_log WHERE day = :day")
    suspend fun get(day: Long): StudyLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: StudyLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<StudyLogEntity>)

    @Query("DELETE FROM study_log")
    suspend fun clear()
}
