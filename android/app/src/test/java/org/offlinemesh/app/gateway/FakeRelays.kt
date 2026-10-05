package org.offlinemesh.app.gateway

import org.json.JSONArray

/** In-memory Nostr relays for tests. A relay can be taken down, made to refuse, go silent, or store
 *  ephemeral events, so the pool's failure handling is exercised without a network. */
class FakeRelayNetwork {
    private val servers = LinkedHashMap<String, FakeRelayServer>()
    fun server(url: String) = servers.getOrPut(url) { FakeRelayServer(url) }
    fun connector() = RelayConnector { url, listener -> server(url).accept(listener) }
}

class FakeRelayServer(val url: String) {
    var up = true
    var silent = false
    var reply: (NostrEvent) -> Pair<Boolean, String> = { true to "" }
    val received = ArrayList<NostrEvent>()
    val stored = ArrayList<NostrEvent>()
    val clients = ArrayList<FakeClient>()
    var connectCount = 0
    var storeEverything = false

    fun accept(listener: RelayListener): RelayConnection {
        connectCount++
        val client = FakeClient(this, listener)
        if (up) {
            clients.add(client)
            listener.onOpen()
        } else {
            client.closed = true
            listener.onClosed("down")
        }
        return client
    }

    fun dropAll(reason: String = "dropped") {
        for (c in clients.toList()) { c.closed = true; c.listener.onClosed(reason) }
        clients.clear()
    }

    internal fun publish(from: FakeClient, e: NostrEvent) {
        received.add(e)
        if (silent) return
        val (ok, msg) = reply(e)
        from.listener.onMessage("[\"OK\",\"${e.id}\",$ok,\"$msg\"]")
        if (!ok) return
        if (e.kind in 7000..9999 || storeEverything) stored.add(e)
        for (c in clients.toList()) c.deliver(e)
    }
}

class FakeClient(private val server: FakeRelayServer, val listener: RelayListener) : RelayConnection {
    var closed = false
    var subId: String? = null
    var filters: List<NostrFilter> = emptyList()
    val sent = ArrayList<String>()

    override fun send(text: String): Boolean {
        if (closed) return false
        sent.add(text)
        val arr = JSONArray(text)
        when (arr.getString(0)) {
            "EVENT" -> {
                val wrapped = "[\"EVENT\",\"x\",${arr.getJSONObject(1)}]"
                val e = (Nostr.parseRelayMessage(wrapped) as RelayMessage.Event).event
                server.publish(this, e)
            }
            "REQ" -> {
                subId = arr.getString(1)
                filters = (2 until arr.length()).map { parseFilter(arr.getJSONObject(it)) }
                for (e in server.stored) deliver(e)
                listener.onMessage("[\"EOSE\",\"$subId\"]")
            }
            "CLOSE" -> { subId = null; filters = emptyList() }
        }
        return true
    }

    override fun close() { closed = true }

    fun deliver(e: NostrEvent) {
        val id = subId ?: return
        if (closed || filters.none { matches(it, e) }) return
        listener.onMessage("[\"EVENT\",\"$id\",${Nostr.eventJson(e)}]")
    }

    private fun matches(f: NostrFilter, e: NostrEvent): Boolean {
        val since = f.since
        val wanted = f.tagValues["t"]
        return e.kind in f.kinds && (since == null || e.createdAt >= since) &&
            (wanted == null || e.tagValue("t") in wanted)
    }

    private fun parseFilter(o: org.json.JSONObject): NostrFilter {
        val kinds = o.getJSONArray("kinds").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val t = o.optJSONArray("#t")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        return NostrFilter(
            kinds, if (t != null) mapOf("t" to t) else emptyMap(),
            since = if (o.has("since")) o.getLong("since") else null,
            limit = if (o.has("limit")) o.getInt("limit") else null,
        )
    }
}
