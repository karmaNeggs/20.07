package org.offlinemesh.app.gateway

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Real WebSocket transport for [RelayPool] over OkHttp. Deliberately thin: all protocol and failure
 * logic lives in the pool, which is tested against fakes. Not used by the app until G2.
 */
class OkHttpRelayConnector(
    private val client: OkHttpClient = defaultClient(),
) : RelayConnector {

    override fun connect(url: String, listener: RelayListener): RelayConnection {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSE, null)
                    listener.onClosed(reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onClosed(t.message ?: "failure")
            },
        )
        return object : RelayConnection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close() { socket.close(NORMAL_CLOSE, null) }
        }
    }

    companion object {
        private const val NORMAL_CLOSE = 1000
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val PING_INTERVAL_SEC = 30L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // a subscription is idle by design; pings detect dead sockets
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .build()
    }
}
