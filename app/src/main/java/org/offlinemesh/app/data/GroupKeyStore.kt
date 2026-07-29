package org.offlinemesh.app.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.offlinemesh.app.crypto.SenderIdentity

/** Group symmetric keys AND per-group Ed25519 sender-identity keypairs, held only in
 *  Android Keystore-backed encrypted prefs, never in Room. Two namespaced prefixes share this one
 *  prefs file rather than a second `EncryptedSharedPreferences` instance — a second instance would
 *  mean a second `MasterKey`/Keystore round-trip for no benefit, since both are the same "secret
 *  this group needs, gone the moment the group is dismantled" shape. [allGroupIds] deliberately
 *  reads only the [KEY_PREFIX] namespace so it keeps meaning exactly what it always has ("every
 *  group id currently holding a stored SYMMETRIC key") for [GroupRepository.sweepOrphanKeys] —
 *  without that, a `"$groupId:$SIGN_PREFIX"`-shaped entry would be misread as its own bogus
 *  "group id" that can never match a real one, and get swept the moment it's written. */
class GroupKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "mesh_group_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putKey(groupId: String, key: ByteArray) {
        prefs.edit().putString(keyPrefKey(groupId), Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun getKey(groupId: String): ByteArray? =
        prefs.getString(keyPrefKey(groupId), null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun removeKey(groupId: String) {
        prefs.edit().remove(keyPrefKey(groupId)).apply()
    }

    /** Persists [pair] for [groupId] — public and private key concatenated (both fixed-length, 32
     *  bytes each per Ed25519) into one Base64 string rather than two separate prefs entries, so
     *  the pair can never partially exist (one half written, the other missing after a crash
     *  between two `apply()` calls). */
    fun putSigningKeyPair(groupId: String, pair: SenderIdentity.Ed25519KeyPair) {
        val combined = pair.publicKey + pair.privateKey
        prefs.edit().putString(signPrefKey(groupId), Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun getSigningKeyPair(groupId: String): SenderIdentity.Ed25519KeyPair? {
        val stored = prefs.getString(signPrefKey(groupId), null) ?: return null
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        if (combined.size != ED25519_PUBLIC_KEY_LEN + ED25519_PRIVATE_KEY_LEN) return null
        val publicKey = combined.copyOfRange(0, ED25519_PUBLIC_KEY_LEN)
        val privateKey = combined.copyOfRange(ED25519_PUBLIC_KEY_LEN, combined.size)
        return SenderIdentity.Ed25519KeyPair(publicKey, privateKey)
    }

    fun removeSigningKeyPair(groupId: String) {
        prefs.edit().remove(signPrefKey(groupId)).apply()
    }

    /** Every group id currently holding a stored SYMMETRIC key — used by
     *  [GroupRepository.sweepOrphanKeys] to find keys with no matching group row left (e.g. after
     *  a destructive Room schema migration, which wipes the `groups` table but not this separate
     *  encrypted-prefs store). Reads only [KEY_PREFIX]-namespaced entries and strips the prefix
     *  back off, so a signing-keypair entry for the same group never gets misread as a second,
     *  bogus group id (see class doc). */
    fun allGroupIds(): Set<String> =
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.toSet()

    companion object {
        // Both namespaced so the same prefs file can hold a group's symmetric key and its
        // Ed25519 signing keypair side by side without either misreading the other's entries as
        // its own — see class doc.
        private const val KEY_PREFIX = "key:"
        private const val SIGN_PREFIX = "sign:"
        private const val ED25519_PUBLIC_KEY_LEN = 32
        private const val ED25519_PRIVATE_KEY_LEN = 32

        private fun keyPrefKey(groupId: String) = "$KEY_PREFIX$groupId"
        private fun signPrefKey(groupId: String) = "$SIGN_PREFIX$groupId"
    }
}
