package ru.slovarik.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, SetEntity::class, CardEntity::class, StudyLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun setDao(): SetDao
    abstract fun cardDao(): CardDao
    abstract fun studyLogDao(): StudyLogDao
}
