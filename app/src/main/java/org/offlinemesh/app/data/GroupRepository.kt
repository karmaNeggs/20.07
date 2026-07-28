package org.offlinemesh.app.data

import android.content.Context
import java.util.UUID

class GroupRepository(context: Context) {
    private val db = AppDatabase.get(context)
    // Lazy, not eager: building this touches the Android Keystore (via GroupKeyStore's
    // EncryptedSharedPreferences/MasterKey), real work that's wasted if a GroupRepository is ever
    // constructed without actually reading/writing a key this session — and, found while testing
    // WifiDirectHandoffCoordinator, the Keystore provider isn't available under Robolectric at
    // all (NoSuchAlgorithmException), so eager construction made GroupRepository impossible to
    // construct in that test environment even though none of its exercised code paths ever touch
    // key storage.
    private val keyStore by lazy { GroupKeyStore(context) }
    val groupDao = db.groupDao()
    private val sosDao = db.sosDao()
    private val evidenceDao = db.evidenceDao()
    private val evidenceChunkDao = db.evidenceChunkDao()
    private val nicknameDao = db.nicknameDao()

    /** deviceId identifies this phone within groups; random per-install, never tied to real identity. */
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("mesh_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    /** Creates a brand-new group with a random id+key, and returns the shareable code for it. */
    suspend fun createGroup(name: String): Pair<GroupEntity, String> {
        val parsed = JoinCode.generate(name)
        keyStore.putKey(parsed.groupId, parsed.key)
        val group = GroupEntity(id = parsed.groupId, name = name, createdAt = System.currentTimeMillis())
        groupDao.insert(group)
        return group to JoinCode.encode(parsed)
    }

    /** Joins a group from someone else's shared code (or a mesh2007://join?c=... link). Null if malformed. */
    suspend fun joinGroup(rawCode: String): GroupEntity? {
        val parsed = JoinCode.decode(JoinCode.extractCode(rawCode)) ?: return null
        keyStore.putKey(parsed.groupId, parsed.key)
        val group = GroupEntity(id = parsed.groupId, name = parsed.name, createdAt = System.currentTimeMillis())
        groupDao.insert(group)
        return group
    }

    fun getGroupKey(groupId: String): ByteArray? = keyStore.getKey(groupId)

    /**
     * Reconstructs the exact same invite code any member could show — there's no "owner" role
     * in this design. Whoever joined has the full (id, key, name) stored locally already, so
     * every member can invite new people just as well as whoever originally created it. This is
     * what lets a group outlive its creator deleting their own copy or going offline for good.
     */
    suspend fun getShareCode(groupId: String): String? {
        val group = groupDao.getGroup(groupId) ?: return null
        val key = keyStore.getKey(groupId) ?: return null
        return JoinCode.encode(JoinCode.Parsed(groupId, key, group.name))
    }

    /** Actually deletes the group and everything relayed for it — not just hides it. */
    suspend fun dismantleGroup(groupId: String) {
        for (evidenceId in evidenceDao.idsForGroup(groupId)) {
            evidenceChunkDao.deleteForEvidence(evidenceId)
        }
        evidenceDao.deleteForGroup(groupId)
        sosDao.deleteForGroup(groupId)
        nicknameDao.deleteForGroup(groupId)
        groupDao.delete(groupId)
        keyStore.removeKey(groupId)
    }
}
