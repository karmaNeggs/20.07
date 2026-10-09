@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerc

import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.gateway.*
import java.util.Random
import java.util.concurrent.*
import java.util.concurrent.atomic.*

internal const val T0 = 1_700_000_000_000L
internal const val DAY = 86_400_000L

internal object Fx {
    fun tag(i: Int): ByteArray = ByteArray(16).also { var v = i; for (k in 0 until 4) { it[k] = v.toByte(); v = v ushr 8 }; it[15] = 7 }
    fun bytes(r: Random, n: Int) = ByteArray(n).also { r.nextBytes(it) }
    fun frame(tagI: Int, cls: Int, createdSec: Long, inner: ByteArray, flags: Int = 0) =
        MeshFrameCodec.Frame.Uplink(tag(tagI), cls, flags, createdSec, inner)
    fun enc(f: MeshFrameCodec.Frame.Uplink) = MeshFrameCodec.encodeUplink(f.relayTag, f.cls, f.flags, f.createdAtSec, f.inner)
    fun hex64(i: Long) = "%064x".format(i)
    fun event(id: String, tagHex: String = "ab", content: String = "x") =
        NostrEvent(id, "0".repeat(64), 1L, 7007, listOf(listOf("t", tagHex)), content, "0".repeat(128))
    fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { val f = c.getDeclaredField(name); f.isAccessible = true; return f.get(o) } catch (e: NoSuchFieldException) { c = c.superclass }
        }
        error("no field $name")
    }
    fun size(o: Any?): Int = when (o) { is Map<*, *> -> o.size; is Collection<*> -> o.size; else -> error("size of $o") }
}

/** Runs n threads in a loop for [seconds]; returns errors (including a hang report if any thread fails to stop). */
internal fun runThreads(n: Int, seconds: Double, body: (Int, Random) -> Unit): List<Throwable> {
    val errs = ConcurrentLinkedQueue<Throwable>()
    val stop = AtomicBoolean(false)
    val done = CountDownLatch(n)
    val threads = (0 until n).map { i ->
        Thread {
            val rnd = Random(1000L + i)
            try { while (!stop.get()) body(i, rnd) } catch (t: Throwable) { errs.add(t); stop.set(true) } finally { done.countDown() }
        }.also { it.isDaemon = true; it.start() }
    }
    Thread.sleep((seconds * 1000).toLong())
    stop.set(true)
    if (!done.await(30, TimeUnit.SECONDS)) {
        errs.add(AssertionError("HANG/DEADLOCK: " + threads.filter { it.isAlive }.map { t -> t.stackTrace.take(8).joinToString(" <- ") }))
    }
    return errs.toList()
}

internal class FakeConn(val url: String, val listener: RelayListener, val owner: FakeConnector) : RelayConnection {
    @Volatile var closed = false
    override fun send(text: String): Boolean {
        owner.sent.incrementAndGet()
        owner.sentTexts?.add(text)
        return owner.onSend(this, text)
    }
    override fun close() { closed = true }
}

internal class FakeConnector : RelayConnector {
    enum class Ack { NONE, OK, BLOCK, RATE, SEND_FAIL }
    val conns = CopyOnWriteArrayList<FakeConn>()
    val sent = AtomicInteger()
    @Volatile var ack = Ack.OK
    @Volatile var openSync = true
    @Volatile var sentTexts: MutableList<String>? = null
    @Volatile var closeFirstN = 0
    private val idRe = Regex("\"id\":\"([0-9a-f]{64})\"")
    override fun connect(url: String, listener: RelayListener): RelayConnection {
        val c = FakeConn(url, listener, this)
        conns.add(c)
        if (closeFirstN > 0) { closeFirstN--; c.closed = true; listener.onClosed("boom"); return c }
        if (openSync) listener.onOpen()
        return c
    }
    fun onSend(c: FakeConn, text: String): Boolean {
        if (!text.startsWith("[\"EVENT\"")) return true
        val id = idRe.find(text)?.groupValues?.get(1) ?: return true
        return when (ack) {
            Ack.NONE -> true
            Ack.OK -> { c.listener.onMessage("[\"OK\",\"$id\",true,\"\"]"); true }
            Ack.BLOCK -> { c.listener.onMessage("[\"OK\",\"$id\",false,\"blocked: no\"]"); true }
            Ack.RATE -> { c.listener.onMessage("[\"OK\",\"$id\",false,\"rate-limited: slow\"]"); true }
            Ack.SEND_FAIL -> false
        }
    }
    fun remoteClose(c: FakeConn) { c.closed = true; c.listener.onClosed("remote") }
    fun live() = conns.count { !it.closed }
}
