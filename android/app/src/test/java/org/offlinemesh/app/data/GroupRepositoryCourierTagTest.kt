package org.offlinemesh.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.offlinemesh.app.crypto.CryptoUtils
import java.security.SecureRandom

/**
 * Covers [GroupRepository.matchCourierTag] — the pure, `internal` matching core behind
 * [GroupRepository.resolveGroupKeyByCourierTag] (P4 slice 3, `docs/DECISIONS.md` decision 43),
 * mirroring [GroupRepositoryHandleTest]'s exact shape and reasoning for the identical reason (a
 * plain, instant JVM test despite [GroupRepository] itself needing a real Keystore).
 */
class GroupRepositoryCourierTagTest {

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun tagFor(key: ByteArray, epoch: Long) = CryptoUtils.rotatingAdvertisementId(
        key, epoch, CryptoUtils.COURIER_TAG_WINDOW_SECONDS, CryptoUtils.COURIER_TAG_LEN,
    )

    @Test
    fun `matches the right group among several`() {
        val keyA = randomKey()
        val keyB = randomKey()
        val keyC = randomKey()
        val groups = listOf("group-a" to keyA, "group-b" to keyB, "group-c" to keyC)
        val epoch = 1_700_000_000L
        val tag = tagFor(keyB, epoch)

        val resolved = GroupRepository.matchCourierTag(tag, groups, epoch)

        assertEquals("group-b" to keyB, resolved)
    }

    @Test
    fun `returns null when no group's key produces this tag`() {
        val groups = listOf("group-a" to randomKey(), "group-b" to randomKey())
        val tagFromAnUnrelatedKey = tagFor(randomKey(), 1_700_000_000L)

        assertNull(GroupRepository.matchCourierTag(tagFromAnUnrelatedKey, groups, 1_700_000_000L))
    }

    @Test
    fun `returns null against an empty group list without touching anything`() {
        val tag = tagFor(randomKey(), 1_700_000_000L)
        assertNull(GroupRepository.matchCourierTag(tag, emptyList(), 1_700_000_000L))
    }

    @Test
    fun `a tag computed at creation time still resolves up to one day of skew later`() {
        val key = randomKey()
        val createdAtEpochSec = 1_700_000_000L
        val tag = tagFor(key, createdAtEpochSec)
        val groups = listOf("group-a" to key)

        val nextDay = createdAtEpochSec + CryptoUtils.COURIER_TAG_WINDOW_SECONDS

        assertEquals("group-a" to key, GroupRepository.matchCourierTag(tag, groups, nextDay))
    }

    @Test
    fun `a tag computed at the default 6-byte truncation length never matches this group's real tag`() {
        // Regression guard for the one real gotcha a naive copy-paste of matchHandle would
        // introduce: CryptoUtils.candidateAdvertisementIds' own truncateLen defaults to
        // ROTATING_ID_LEN (6, the beacon/GATT-handle length) — matchCourierTag must pass
        // COURIER_TAG_LEN (16) explicitly, or it would silently never match a real courier tag.
        val key = randomKey()
        val epoch = 1_700_000_000L
        val realTag = tagFor(key, epoch) // 16 bytes, what a real sender actually produces
        val wrongLengthTag = CryptoUtils.rotatingAdvertisementId(key, epoch, CryptoUtils.COURIER_TAG_WINDOW_SECONDS)

        assertFalse("a 6-byte-truncated id must not equal the real 16-byte tag", realTag.contentEquals(wrongLengthTag))
        assertEquals(
            "group-a" to key,
            GroupRepository.matchCourierTag(realTag, listOf("group-a" to key), epoch),
        )
    }
}
