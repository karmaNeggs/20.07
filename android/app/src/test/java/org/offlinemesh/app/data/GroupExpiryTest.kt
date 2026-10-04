package org.offlinemesh.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the SQL-level expiry queries added to [GroupDao] (`getActiveGroups`'s `strftime`-based
 * filter, `expiredGroupIds`) by inserting [GroupEntity] rows directly through the DAO — NOT
 * through [GroupRepository.createGroup]/`joinGroup`/`dismantleGroup`, which touch Android
 * Keystore-backed `EncryptedSharedPreferences` via [GroupKeyStore] and are therefore
 * unconstructible under Robolectric (see [org.offlinemesh.app.ble.RelayResponderTest]'s class doc
 * for the same documented constraint, which applies identically here). This file's queries never
 * touch key storage at all, so they're fully testable; a positive "an expired group is actually
 * dismantled end-to-end by [GroupRepository.expireGroups]" test would need a real device or
 * instrumented test, not a plain Robolectric unit test — not attempted here, same honesty this
 * project already applies to its other hardware/Keystore-dependent gaps.
 */
@RunWith(RobolectricTestRunner::class)
class GroupExpiryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dao = AppDatabase.get(context).groupDao()

    @Before
    fun setUp() {
        runBlocking {
            for (id in listOf("g-live", "g-expired", "g-boundary")) dao.delete(id)
        }
    }

    private fun group(id: String, expiresAt: Long) =
        GroupEntity(id = id, name = id, createdAt = System.currentTimeMillis(), expiresAt = expiresAt)

    @Test
    fun `getActiveGroups excludes an already-expired group`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(group("g-live", now + 60_000))
        dao.insert(group("g-expired", now - 60_000))

        val active = dao.getActiveGroups().map { it.id }
        assertTrue(active.contains("g-live"))
        assertTrue("an expired group must not appear in getActiveGroups", !active.contains("g-expired"))
    }

    @Test
    fun `expiredGroupIds returns exactly the groups past the given cutoff, boundary inclusive`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(group("g-live", now + 60_000))
        dao.insert(group("g-expired", now - 60_000))
        dao.insert(group("g-boundary", now)) // exactly at cutoff — expiresAt <= now includes this

        val expired = dao.expiredGroupIds(now).toSet()
        assertEquals(setOf("g-expired", "g-boundary"), expired)
    }

    @Test
    fun `allGroupIds includes an already-expired-but-not-yet-swept group, unlike getActiveGroups`() = runTest {
        // The property GroupRepository.sweepOrphanKeys relies on: an expired-but-not-dismantled
        // group's key must NOT be treated as orphaned (that's expireGroups' job, not this sweep's),
        // so the "which groups still legitimately exist" query used for the orphan check must be
        // the unfiltered one, not getActiveGroups (which excludes expired rows on purpose).
        val now = System.currentTimeMillis()
        dao.insert(group("g-live", now + 60_000))
        dao.insert(group("g-expired", now - 60_000))

        val all = dao.allGroupIds().toSet()
        assertTrue(all.contains("g-live"))
        assertTrue(
            "an expired-but-not-yet-swept group must still count as a live row for orphan-key purposes",
            all.contains("g-expired")
        )
    }

    @Test
    fun `a group well within its lifetime is not reported as expired`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(group("g-live", now + JoinCode.DEFAULT_LIFETIME_MILLIS))
        assertTrue(dao.expiredGroupIds(now).isEmpty())
    }

    @Test
    fun `expireGroups is a safe no-op when nothing has expired`() = runTest {
        // Constructing GroupRepository is safe under Robolectric (GroupKeyStore is lazy — see
        // class doc) — but expireGroups only ever calls dismantleGroup (which touches Keystore)
        // for a group that's ACTUALLY expired. With none present, that path never runs, so this
        // only proves the no-op case doesn't crash or unconditionally touch key storage — not the
        // full dismantle behavior (see class doc).
        val repo = GroupRepository(context)
        repo.expireGroups()
    }
}
