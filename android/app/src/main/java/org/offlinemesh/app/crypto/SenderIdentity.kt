package org.offlinemesh.app.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * Per-sender authentication on top of the group's shared symmetric key. HMAC(group_key)
 * already proves "someone holding this group's key produced this" — but every member holds the
 * same key, so it can't tell members apart: a compromised or malicious member can forge content
 * that looks like it came from anyone else in the group. An Ed25519 keypair is generated per
 * (device, group) — not per-device — so a signature over the same canonical bytes the group HMAC
 * already covers adds "and specifically THIS sender" without weakening or replacing the existing
 * check (see [org.offlinemesh.app.ble.MeshFrameCodec]'s per-frame `mac`/`signature` pair, and
 * [org.offlinemesh.app.ble.RelayResponder]'s pin-on-first-sight verification).
 *
 * Uses Tink's `subtle.Ed25519Sign`/`Ed25519Verify` directly — plain algorithm implementations, not
 * Tink's `KeysetHandle`/registry API, which brings its own key-serialization and management model
 * this app has no use for (keys here are raw bytes, stored the same way [CryptoUtils]' own group
 * keys already are, via `GroupKeyStore`). This also sidesteps most of Tink's R8-full-mode surface:
 * the registry/proto machinery is what typically needs the defensive `-dontwarn` rules seen in the
 * wild, none of which this app's code path ever reaches. These two classes work as pure Java
 * (falling back automatically when no faster native/Conscrypt provider is present — see
 * `proguard-rules.pro`'s `org.conscrypt.**` dontwarn), so this needs nothing beyond minSdk 26's
 * baseline JVM.
 */
object SenderIdentity {

    data class Ed25519KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    fun generateKeyPair(): Ed25519KeyPair {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        return Ed25519KeyPair(pair.publicKey, pair.privateKey)
    }

    /** Signs [data] with [privateKey] — same shape as [CryptoUtils.authTag], data in, tag out. */
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray = Ed25519Sign(privateKey).sign(data)

    /** True only if [signature] is a valid Ed25519 signature over [data] under [publicKey] — false
     *  for any tamper, wrong key, or malformed input, never an exception (mirrors
     *  [CryptoUtils.constantTimeEquals]'s "verification failure is just `false`" shape so callers
     *  don't need a second try/catch on top of this one). */
    // SwallowedException: mirrors CryptoUtils.decrypt's identical "failure is a sentinel value,
    // not something to propagate" contract (see this function's own doc) — a bad signature isn't
    // exceptional here, it's the expected shape of "someone forged this."
    @Suppress("SwallowedException")
    fun verify(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean =
        try {
            Ed25519Verify(publicKey).verify(signature, data)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
}
