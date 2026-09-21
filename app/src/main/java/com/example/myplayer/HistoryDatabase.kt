package com.example.myplayer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HistoryEntity::class,
        BookmarkEntity::class,
        DownloadEntity::class,
        SubscriptionEntity::class,
        VideoNoteEntity::class,
        SavedPlaylistEntity::class,
        SavedPlaylistItemEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun videoNoteDao(): VideoNoteDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "history.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
