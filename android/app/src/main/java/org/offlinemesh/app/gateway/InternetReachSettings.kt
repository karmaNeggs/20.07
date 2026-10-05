package org.offlinemesh.app.gateway

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single "Internet reach" switch (`PLAN-v2.md` §13.12, decision 68), **off by default**, plus the
 * persisted throwaway gateway key. Plain SharedPreferences is enough: the switch is not secret, and the
 * gateway key protects no group content (it only signs relay events and is replaced every 7 days).
 */
class InternetReachSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /** The persisted gateway key and when it was created, or null if none. */
    fun savedKey(): Pair<ByteArray, Long>? {
        val hex = prefs.getString(KEY_SECRET, null) ?: return null
        val secret = hex.hexToBytes() ?: return null
        return secret to prefs.getLong(KEY_CREATED, 0L)
    }

    fun saveKey(secret: ByteArray, createdAtMs: Long) {
        prefs.edit().putString(KEY_SECRET, secret.toHex()).putLong(KEY_CREATED, createdAtMs).apply()
    }

    companion object {
        private const val PREFS = "internet_reach"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SECRET = "gateway_key"
        private const val KEY_CREATED = "gateway_key_created_ms"

        /** Public relays tried in parallel (decision 67 rule 3). Availability changes hour to hour. */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.snort.social", "wss://nos.lol", "wss://nostr.mom",
            "wss://relay.primal.net", "wss://relay.damus.io",
        )
    }
}
