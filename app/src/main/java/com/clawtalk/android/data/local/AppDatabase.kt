package com.clawtalk.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.clawtalk.android.data.local.entity.MessageEntity

@Database(entities = [MessageEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
