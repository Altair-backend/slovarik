package ru.slovarik.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.slovarik.app.data.Repository
import ru.slovarik.app.data.SettingsStore
import ru.slovarik.app.data.db.AppDatabase
import ru.slovarik.app.data.db.CardDao
import ru.slovarik.app.data.db.FolderDao
import ru.slovarik.app.data.db.SetDao
import ru.slovarik.app.data.db.StudyLogDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "slovarik.db").build()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideSetDao(db: AppDatabase): SetDao = db.setDao()

    @Provides
    fun provideCardDao(db: AppDatabase): CardDao = db.cardDao()

    @Provides
    fun provideStudyLogDao(db: AppDatabase): StudyLogDao = db.studyLogDao()
}

/**
 * Точка входа Hilt для кода, который создаёт система и в который нельзя внедрить
 * зависимости через конструктор: виджеты Glance и воркеры WorkManager.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun repository(): Repository
    fun settings(): SettingsStore
}

fun Context.appEntryPoint(): AppEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
