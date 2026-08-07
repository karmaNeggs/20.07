package org.offlinemesh.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.offlinemesh.app.ble.RelayEngine
import org.offlinemesh.app.crypto.CryptoUtils
import java.security.SecureRandom

/**
 * Covers [GroupRepository.matchHandle] — the pure, `internal` matching core behind
 * [GroupRepository.resolveGroupKeyByHandle] (decision 38, `docs/DECISIONS.md`), split out
 * specifically so this can be a plain, instant JVM test despite [GroupRepository] itself needing a
 * real Android `Context`/Keystore (see that class's own `keyStore` doc). No DAO, no Keystore, no
 * Robolectric — just the same HMAC-and-compare logic `resolveGroupKeyByHandle` wraps with real
 * group/key lookups.
 */
class GroupRepositoryHandleTest {

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `matches the right group among several`() {
        val keyA = randomKey()
        val keyB = randomKey()
        val keyC = randomKey()
        val groups = listOf("group-a" to keyA, "group-b" to keyB, "group-c" to keyC)
        val epoch = 1_700_000_000L
        val handle = CryptoUtils.rotatingAdvertisementId(keyB, epoch, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS)

        val resolved = GroupRepository.matchHandle(handle, groups, epoch)

        assertEquals("group-b" to keyB, resolved)
    }

    @Test
    fun `returns null when no group's key produces this handle`() {
        val groups = listOf("group-a" to randomKey(), "group-b" to randomKey())
        val handleFromAnUnrelatedKey = CryptoUtils.rotatingAdvertisementId(
            randomKey(), 1_700_000_000L, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
        )

        assertNull(GroupRepository.matchHandle(handleFromAnUnrelatedKey, groups, 1_700_000_000L))
    }

    @Test
    fun `returns null against an empty group list without touching anything`() {
        // The concrete case that makes the entire blind-relay path testable under Robolectric for
        // the first time (see RelayResponderTest) — zero joined groups means matchHandle's own loop
        // never runs at all, so resolveGroupKeyByHandle never needs a real GroupRepository/Keystore
        // to safely return "not ours."
        val handle = CryptoUtils.rotatingAdvertisementId(
            randomKey(), 1_700_000_000L, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
        )
        assertNull(GroupRepository.matchHandle(handle, emptyList(), 1_700_000_000L))
    }

    @Test
    fun `a handle computed at creation time still resolves when checked up to just under 48h later`() {
        // The empirical proof of the 72h window derivation (CryptoUtils.
        // GATT_GROUP_HANDLE_WINDOW_SECONDS's own doc): a handle is computed ONCE, at
        // creation/first-ingest, and must still resolve correctly against this app's own 48h
        // content-retention ceiling (RelayEngine.CONTENT_MAX_AGE_MILLIS) — not just argued in a
        // comment, checked here against the real constant.
        val key = randomKey()
        val createdAtEpochSec = 1_700_000_000L
        val handle = CryptoUtils.rotatingAdvertisementId(
            key, createdAtEpochSec, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
        )
        val groups = listOf("group-a" to key)

        val justUnder48h = createdAtEpochSec + (RelayEngine.CONTENT_MAX_AGE_MILLIS / 1000) - 60

        assertEquals("group-a" to key, GroupRepository.matchHandle(handle, groups, justUnder48h))
    }
}
