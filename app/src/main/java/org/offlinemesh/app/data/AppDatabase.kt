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
    // v7: added SosEntity.hop (PLAN-v2.md P1 — decouples hop-from-origin from ttl, which a
    // degree-aware relay may now drop by more than 1 per hop; see docs/DECISIONS.md decision 16).
    // v8: added SosEntity.isAlert (docs/DECISIONS.md decision 35) — splits the loud/broadcast alert
    // treatment from ordinary quiet messages, which share this same table/entity unchanged.
    // v9: SosEntity.mac/signature replaced with `sealed` (docs/DECISIONS.md decision 37) — SOS
    // message content is now AES-GCM sealed, not cleartext-plus-HMAC.
    version = 9,
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
