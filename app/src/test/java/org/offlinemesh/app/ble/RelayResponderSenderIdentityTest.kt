package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.crypto.SenderIdentity

/**
 * Covers [RelayResponder.checkSenderKeyPin]/[RelayResponder.signatureCheckPasses] (sender identity)
 * in isolation — deliberately NOT part of [RelayResponderTest] (Robolectric-backed):
 * exercising the real pin-then-verify flow through [RelayResponder.handleIncoming] would need
 * [org.offlinemesh.app.data.GroupRepository.getGroupKey]/`getSenderKeyPair`, both Android
 * Keystore-backed and unavailable under Robolectric (see [RelayResponderTest]'s own class doc for
 * the same constraint) — every one of `handleSos`/`handleEvidMeta`/`handleNickname`/
 * `handlePresence`/`handlePositionSealed` hits that wall via `authOk` before ever reaching this
 * logic. The actual pin/verify DECISIONS these two functions make have no such dependency and are
 * fully testable directly, same as [RelayResponder.presenceWithinSkew]/`selectPositionsToRelay`.
 */
class RelayResponderSenderIdentityTest {

    // ---------- checkSenderKeyPin ----------

    @Test
    fun `first sight of a key with no existing pin is OK`() {
        val pair = SenderIdentity.generateKeyPair()
        assertEquals(
            RelayResponder.SenderKeyPinResult.OK,
            RelayResponder.checkSenderKeyPin(existingPublicKey = null, incomingPublicKey = pair.publicKey)
        )
    }

    @Test
    fun `no incoming key at all (a peer not yet carrying one) is OK regardless of any existing pin`() {
        val pinned = SenderIdentity.generateKeyPair()
        assertEquals(
            RelayResponder.SenderKeyPinResult.OK,
            RelayResponder.checkSenderKeyPin(existingPublicKey = pinned.publicKey, incomingPublicKey = null)
        )
        assertEquals(
            RelayResponder.SenderKeyPinResult.OK,
            RelayResponder.checkSenderKeyPin(existingPublicKey = null, incomingPublicKey = null)
        )
    }

    @Test
    fun `an incoming key matching the existing pin is OK`() {
        val pair = SenderIdentity.generateKeyPair()
        assertEquals(
            RelayResponder.SenderKeyPinResult.OK,
            RelayResponder.checkSenderKeyPin(
                existingPublicKey = pair.publicKey.copyOf(), incomingPublicKey = pair.publicKey
            )
        )
    }

    @Test
    fun `an incoming key different from the existing pin is a MISMATCH`() {
        val pinned = SenderIdentity.generateKeyPair()
        val impostor = SenderIdentity.generateKeyPair()
        assertEquals(
            RelayResponder.SenderKeyPinResult.MISMATCH,
            RelayResponder.checkSenderKeyPin(
                existingPublicKey = pinned.publicKey, incomingPublicKey = impostor.publicKey
            )
        )
    }

    // ---------- signatureCheckPasses ----------

    @Test
    fun `no signature at all is tolerated regardless of a pinned key`() {
        val pinned = SenderIdentity.generateKeyPair()
        assertTrue(
            RelayResponder.signatureCheckPasses(
                pinnedPublicKey = pinned.publicKey, signature = null, signedData = "data".toByteArray()
            )
        )
    }

    @Test
    fun `a signature with no pinned key yet is tolerated`() {
        val pair = SenderIdentity.generateKeyPair()
        val data = "data".toByteArray()
        val signature = SenderIdentity.sign(pair.privateKey, data)
        assertTrue(
            RelayResponder.signatureCheckPasses(pinnedPublicKey = null, signature = signature, signedData = data)
        )
    }

    @Test
    fun `a genuine signature against the pinned key passes`() {
        val pair = SenderIdentity.generateKeyPair()
        val data = "data".toByteArray()
        val signature = SenderIdentity.sign(pair.privateKey, data)
        assertTrue(
            RelayResponder.signatureCheckPasses(
                pinnedPublicKey = pair.publicKey, signature = signature, signedData = data
            )
        )
    }

    @Test
    fun `a signature from an impostor key fails against the real pinned key (mandatory once pinned)`() {
        val real = SenderIdentity.generateKeyPair()
        val impostor = SenderIdentity.generateKeyPair()
        val data = "data".toByteArray()
        val forgedSignature = SenderIdentity.sign(impostor.privateKey, data)
        assertFalse(
            RelayResponder.signatureCheckPasses(
                pinnedPublicKey = real.publicKey, signature = forgedSignature, signedData = data
            )
        )
    }

    @Test
    fun `a genuine signature over different data than what's pinned fails`() {
        val pair = SenderIdentity.generateKeyPair()
        val signature = SenderIdentity.sign(pair.privateKey, "original".toByteArray())
        assertFalse(
            RelayResponder.signatureCheckPasses(
                pinnedPublicKey = pair.publicKey, signature = signature, signedData = "tampered".toByteArray()
            )
        )
    }
}
