package org.offlinemesh.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GroupEntity::class,
        SeenMessageEntity::class,
        SosEntity::class,
        EvidenceEntity::class,
        EvidenceChunkEntity::class,
        NicknameEntity::class
    ],
    version = 4, // v4: added NicknameEntity (per-group display name)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun sosDao(): SosDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun evidenceChunkDao(): EvidenceChunkDao
    abstract fun nicknameDao(): NicknameDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mesh.db"
                )
                    // Pre-release testing app, nothing worth preserving across a schema change
                    // yet — recreate tables rather than writing real migrations at this stage.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
