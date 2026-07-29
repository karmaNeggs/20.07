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
        NicknameEntity::class,
        PeerKeyEntity::class
    ],
    // v6: added PeerKeyEntity (pinned per-sender Ed25519 public keys) and SosEntity/EvidenceEntity/
    // NicknameEntity.signature (Ed25519 sender identity).
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun sosDao(): SosDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun evidenceChunkDao(): EvidenceChunkDao
    abstract fun nicknameDao(): NicknameDao
    abstract fun peerKeyDao(): PeerKeyDao

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
