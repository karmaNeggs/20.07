package org.offlinemesh.app.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Group symmetric keys, held only in Android Keystore-backed encrypted prefs, never in Room. */
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
        prefs.edit().putString(groupId, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun getKey(groupId: String): ByteArray? =
        prefs.getString(groupId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun removeKey(groupId: String) {
        prefs.edit().remove(groupId).apply()
    }
}
