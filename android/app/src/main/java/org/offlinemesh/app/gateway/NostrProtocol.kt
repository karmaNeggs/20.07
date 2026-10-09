package org.offlinemesh.app.gateway

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    // Canonical lowercase hex only: toInt(16) would accept signs, and uppercase is a second spelling of the same bytes.
    if (length % 2 != 0 || !all { it in '0'..'9' || it in 'a'..'f' }) return null
    return try {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
    } catch (e: NumberFormatException) {
        null
    }
}

private const val HEX_RADIX = 16

/** One signed Nostr event (NIP-01). [tags] is a list of string lists, e.g. `["t", "ab12cd"]`. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag named [name], or null. */
    fun tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}

/** A subscription filter. [tagValues] maps a single-letter tag name to accepted values (`#t`). */
data class NostrFilter(
    val kinds: List<Int>,
    val tagValues: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val limit: Int? = null,
)

sealed class RelayMessage {
    data class Event(val subId: String, val event: NostrEvent) : RelayMessage()
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class Eose(val subId: String) : RelayMessage()
    data class Notice(val text: String) : RelayMessage()
    data class Closed(val subId: String, val message: String) : RelayMessage()
}

/**
 * Nostr wire format for v3 (`PLAN-v2.md` §13.5): build and sign events, build REQ/EVENT/CLOSE messages,
 * parse relay messages. Pure and synchronous: no sockets (see [RelayPool]).
 *
 * **Serialization is hand-written on purpose.** NIP-01 fixes the exact bytes the event id is hashed
 * over, and `org.json` escapes `/` as `\/`, which would change the id of any event whose content holds
 * a `/` (base64 does). Outbound JSON is therefore built here; only inbound messages use `org.json`.
 */
// TooManyFunctions: one small function per NIP-01 message or field shape (sign, id, verify, three builders,
// parser, escaping), kept together because they are one wire format.
@Suppress("TooManyFunctions")
object Nostr {
    /** Relay messages larger than this are dropped unparsed (a relay streaming junk cannot hurt us). */
    const val MAX_MESSAGE_CHARS = 200_000
    private const val MAX_JSON_DEPTH = 32
    private const val AUX_BYTES = 32
    private const val CONTROL_CHAR_LIMIT = 0x20
    private val random = SecureRandom()

    // LongParameterList: these are exactly the fields NIP-01 signs, plus the aux randomness tests pin.
    @Suppress("LongParameterList")
    fun signEvent(
        secretKey: ByteArray,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        aux: ByteArray = ByteArray(AUX_BYTES).also { random.nextBytes(it) },
    ): NostrEvent {
        val pubkey = Bip340.publicKey(secretKey).toHex()
        val id = computeId(pubkey, createdAt, kind, tags, content)
        val sig = Bip340.sign(id.hexToBytes()!!, secretKey, aux).toHex()
        return NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
    }

    fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val serialized = "[0,${jsonString(pubkey)},$createdAt,$kind,${tagsJson(tags)},${jsonString(content)}]"
        return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** True only if the id really is the hash of the event's own fields AND the signature verifies. */
    fun verifyEvent(e: NostrEvent): Boolean {
        val id = e.id.hexToBytes()
        val pub = e.pubkey.hexToBytes()
        val sig = e.sig.hexToBytes()
        val idMatches = e.id == computeId(e.pubkey, e.createdAt, e.kind, e.tags, e.content)
        return id != null && pub != null && sig != null && idMatches && Bip340.verify(id, pub, sig)
    }

    fun eventJson(e: NostrEvent): String =
        "{\"id\":${jsonString(e.id)},\"pubkey\":${jsonString(e.pubkey)},\"created_at\":${e.createdAt}," +
            "\"kind\":${e.kind},\"tags\":${tagsJson(e.tags)},\"content\":${jsonString(e.content)}," +
            "\"sig\":${jsonString(e.sig)}}"

    fun eventMessage(e: NostrEvent): String = "[\"EVENT\",${eventJson(e)}]"

    fun reqMessage(subId: String, filters: List<NostrFilter>): String =
        "[\"REQ\",${jsonString(subId)}" + filters.joinToString("") { "," + filterJson(it) } + "]"

    fun closeMessage(subId: String): String = "[\"CLOSE\",${jsonString(subId)}]"

    /** Parses one relay-to-client message; null for anything malformed or not understood. */
    // MagicNumber/SwallowedException: NIP-01 messages are fixed-position arrays, and a malformed message from
    // an untrusted relay is expected input that simply yields null, not an error to propagate.
    @Suppress("MagicNumber", "SwallowedException")
    fun parseRelayMessage(text: String): RelayMessage? {
        if (text.length > MAX_MESSAGE_CHARS || nestingDepth(text) > MAX_JSON_DEPTH) return null
        return try {
            val arr = JSONArray(text)
            when (arr.optString(0)) {
                "EVENT" -> parseEventMessage(arr)
                "OK" -> RelayMessage.Ok(arr.getString(1), arr.getBoolean(2), arr.optString(3, ""))
                "EOSE" -> RelayMessage.Eose(arr.getString(1))
                "NOTICE" -> RelayMessage.Notice(arr.optString(1, ""))
                "CLOSED" -> RelayMessage.Closed(arr.getString(1), arr.optString(2, ""))
                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun parseEventMessage(arr: JSONArray): RelayMessage? {
        val o = arr.getJSONObject(2)
        val tagsArr = o.getJSONArray("tags")
        val tags = (0 until tagsArr.length()).map { i ->
            val t = tagsArr.getJSONArray(i)
            (0 until t.length()).map { j -> t.optString(j, "") }
        }
        val event = NostrEvent(
            o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"),
            tags, o.getString("content"), o.getString("sig"),
        )
        return RelayMessage.Event(arr.getString(1), event)
    }

    /** Deepest bracket nesting; 90,000 nested brackets from a hostile relay would overflow the parser's stack. */
    private fun nestingDepth(text: String): Int {
        var depth = 0
        var max = 0
        for (c in text) {
            if (c == '[' || c == '{') { depth++; if (depth > max) max = depth } else if (c == ']' || c == '}') depth--
        }
        return max
    }

    private fun filterJson(f: NostrFilter): String {
        val parts = ArrayList<String>()
        parts.add("\"kinds\":[${f.kinds.joinToString(",")}]")
        for ((name, values) in f.tagValues) {
            parts.add("${jsonString("#$name")}:[${values.joinToString(",") { jsonString(it) }}]")
        }
        f.since?.let { parts.add("\"since\":$it") }
        f.limit?.let { parts.add("\"limit\":$it") }
        return "{" + parts.joinToString(",") + "}"
    }

    private fun tagsJson(tags: List<List<String>>): String =
        "[" + tags.joinToString(",") { tag -> "[" + tag.joinToString(",") { jsonString(it) } + "]" } + "]"

    /** NIP-01 string escaping: only `"` `\` and control characters are escaped; `/` and non-ASCII are not. */
    internal fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c.code < CONTROL_CHAR_LIMIT -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
