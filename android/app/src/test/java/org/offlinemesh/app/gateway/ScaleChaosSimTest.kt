@file:Suppress(
    "MaxLineLength", "TooManyFunctions", "MagicNumber", "LongParameterList", "EmptyFunctionBlock", "LongMethod",
    "CyclomaticComplexMethod", "NestedBlockDepth", "EmptyElseBlock", "ReturnCount", "LoopWithTooManyJumpStatements", "ComplexCondition",
)

package org.offlinemesh.app.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkBatch
import org.offlinemesh.app.ble.UplinkGateway
import java.io.File
import java.util.Random

/**
 * Logical scale and chaos simulation (PLAN-v2.md Part 14.3-14.4). Runs the REAL controller, gateway, priorities,
 * budgets and tag derivation for hundreds of phones against a MODEL of public relays (capacity, per-address limits,
 * bans, outages). Signing, sockets and BLE are not modelled. Findings are about logic and amplification, not real
 * relay behaviour. Report: build/scale-sim-report.txt.
 */
class ScaleChaosSimTest {
    private class Stored(val tagHex: String, val cls: Int, val content: ByteArray, val expiresAt: Long)
    private class Delivery(val at: Long, val phone: Int, val content: ByteArray)
    private class Sent(val t0: Long, val group: Int, val alert: Boolean, val sender: Int)

    private class Relay(val capEv: Int, val capBytes: Int, val perIp: Int) {
        var up = true
        val subs = HashMap<String, MutableSet<Int>>()
        val stored = ArrayList<Stored>()
        var ev = 0; var bytes = 0
        val ipEv = HashMap<Int, Int>(); val ipRej = HashMap<Int, Int>(); val banned = HashMap<Int, Long>()
        var peakEv = 0; var peakBytes = 0; var totalEv = 0L; var totalBytes = 0L; var rejected = 0L; var bans = 0
        fun newSecond() { peakEv = maxOf(peakEv, ev); peakBytes = maxOf(peakBytes, bytes); ev = 0; bytes = 0; ipEv.clear() }
        /** 0 accepted, 1 rejected, 2 banned (the phone then stops using this relay for 10 minutes, like RelayPool). */
        fun publish(now: Long, ip: Int, size: Int): Int {
            if (!up) return 1
            if ((banned[ip] ?: 0L) > now) { rejected++; return 2 }
            val c = (ipEv[ip] ?: 0) + 1; ipEv[ip] = c
            if (c > perIp) {
                rejected++
                val r = (ipRej[ip] ?: 0) + 1; ipRej[ip] = r
                if (r >= 5) { banned[ip] = now + 60_000; ipRej[ip] = 0; bans++; return 2 }
                return 1
            }
            if (ev + 1 > capEv || bytes + size > capBytes) { rejected++; return 1 }
            ev++; bytes += size; totalEv++; totalBytes += size
            return 0
        }
    }

    private inner class World(val nPhones: Int, val groupSize: Int, val onlineFraction: Double, val bridgeAll: Boolean, val ipShare: Int, val relaysPerEvent: Int = 2, capEv: Int = 25, capBytes: Int = 120_000, perIp: Int = 8) {
        var nowMs = 1_800_000_000_000L
        val rnd = Random(42)
        val relays = List(3) { Relay(capEv = capEv, capBytes = capBytes, perIp = perIp) }
        val pending = ArrayList<Delivery>()
        val phones = ArrayList<Phone>()
        val sent = HashMap<Int, Sent>()
        val received = HashMap<Int, HashMap<Int, Long>>()   // msgId -> phone -> time
        val groups = (nPhones + groupSize - 1) / groupSize
        val online = HashSet<Int>()
        var counter = 0
        var maxPending = 0
        val keys = List(groups) { g -> ByteArray(32) { (g * 7 + it).toByte() } }
        fun membersOf(g: Int) = (0 until groupSize).map { g * groupSize + it }.filter { it < nPhones }
        fun onlineMembers(g: Int) = membersOf(g).filter { it in online }

        fun rndFrame(n: Int): ByteArray { val i = counter++; return ByteArray(n) { (i * 31 + it).toByte() }.also { it[0] = (i and 0xFF).toByte(); it[1] = ((i shr 8) and 0xFF).toByte(); it[2] = ((i shr 16) and 0xFF).toByte(); it[3] = 9 } }

        inner class Link(val id: Int, val ip: Int) : UplinkLink {
            val gateway = UplinkGateway({ nowMs }, UplinkGateway.Config(liveKeepPerTag = 24, framesPerTagPerMinute = 180))
            var inject: (ByteArray) -> Unit = {}
            val recent = ArrayDeque<Pair<Long, Boolean>>()
            val backoff = PublishBackoff(kotlin.random.Random(id))
            val relayBannedUntil = LongArray(3)
            override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink) = gateway.offer(frame)
            override fun setInterestTags(tags: Collection<ByteArray>) {
                for (t in tags) {
                    val hex = t.toHex()
                    for (r in relays) {
                        if (!r.up) continue
                        if (r.subs.getOrPut(hex) { HashSet() }.add(id)) {
                            for (s in r.stored) if (s.tagHex == hex && s.expiresAt > nowMs) pending.add(Delivery(nowMs + 700, id, s.content))
                        }
                    }
                }
            }
            override fun tick() {
                maxPending = maxOf(maxPending, gateway.pendingCount())
                if (!hasConnectedRelay() || (backoff.blocked(nowMs) && !gateway.hasPending(MeshFrameCodec.UPLINK_CLASS_ALERT))) return
                val b = gateway.drain(1).firstOrNull() ?: return
                val hex = b.relayTag.toHex()
                val content = b.content()
                val upIdx = relays.indices.filter { relays[it].up && relayBannedUntil[it] <= nowMs }
                val chosen = RelayChoice.pick(hex, upIdx.map { "r$it" }, if (relaysPerEvent > 0) relaysPerEvent else upIdx.size).map { it.substring(1).toInt() }
                var ok = false
                var delivered = false
                for (i in chosen) {
                    val r = relays[i]
                    val code = r.publish(nowMs, ip, content.size)
                    if (code == 2) relayBannedUntil[i] = nowMs + 600_000
                    if (code == 0) {
                        ok = true
                        if (b.cls != MeshFrameCodec.UPLINK_CLASS_LIVE && b.cls < MeshFrameCodec.UPLINK_CLASS_FILE_META) r.stored.add(Stored(hex, b.cls, content, nowMs + 600_000))
                        if (!delivered) {
                            delivered = true
                            r.subs[hex]?.forEach { p -> pending.add(Delivery(nowMs + 700, p, content)) }
                        }
                    }
                }
                recent.addLast(nowMs to ok)
                while (recent.isNotEmpty() && nowMs - recent.first().first > 20_000) recent.removeFirst()
                if (!ok) { gateway.requeue(b); backoff.failed(nowMs) } else backoff.succeeded()
            }
            override fun close() {}
            override fun hasConnectedRelay() = id in online && relays.indices.any { relays[it].up && relayBannedUntil[it] <= nowMs }
            override fun congested(): Boolean = recent.size >= 3 && recent.count { !it.second } * 2 >= recent.size
            fun deliver(content: ByteArray) { for (f in gateway.onRelayBatch(content)) inject(f) }
        }

        inner class Src(val id: Int, val group: Int) : UplinkSource {
            val msgs = ArrayList<MailboxItem>()
            var file: FileUplink? = null
            private fun bridged(): Int {
                val offline = membersOf(group).size - onlineMembers(group).size
                return if (bridgeAll) groupSize - 1 else offline
            }
            override suspend fun groups() = listOf(UplinkGroup("g$group", keys[group]))
            override suspend fun liveFrames(groupId: String) = List(1 + bridged()) { rndFrame(300) }
            override suspend fun mailboxItems(groupId: String) = msgs.toList()
            override suspend fun lastKnownFrames(groupId: String) = List(1 + bridged()) { rndFrame(300) }
            override suspend fun fileUplinks(groupId: String) = listOfNotNull(file)
        }

        inner class Phone(val id: Int) {
            val group = id / groupSize
            val src = Src(id, group)
            val link = Link(id, if (ipShare > 0) id % ipShare else id)
            val controller = InternetReachController(
                src, { inj -> link.also { it.inject = inj } },
                { _, inner -> if (inner.size >= 5 && inner[4] == 1.toByte()) { val mid = (inner[0].toInt() and 0xFF) or ((inner[1].toInt() and 0xFF) shl 8) or ((inner[2].toInt() and 0xFF) shl 16); received.getOrPut(mid) { HashMap() }.putIfAbsent(id, nowMs) } },
                { nowMs }, InternetReachController.Config(random = kotlin.random.Random(id)), bulkAllowed = { true },
            )
        }

        init { for (i in 0 until nPhones) phones.add(Phone(i)) }
        fun goOnline(i: Int) { online.add(i); phones[i].controller.start() }
        fun goOffline(i: Int) { online.remove(i); phones[i].controller.stop() }
        fun startRandomOnline() { for (i in 0 until nPhones) if (rnd.nextDouble() < onlineFraction) goOnline(i) }

        fun send(g: Int, alert: Boolean, fromMember: Int? = null): Int? {
            val members = membersOf(g)
            val sender = fromMember ?: members[rnd.nextInt(members.size)]
            val id = counter++
            val inner = ByteArray(80) { 5 }.also { it[0] = (id and 0xFF).toByte(); it[1] = ((id shr 8) and 0xFF).toByte(); it[2] = ((id shr 16) and 0xFF).toByte(); it[3] = 7; it[4] = 1 }
            val carrier = if (sender in online) sender else onlineMembers(g).firstOrNull() ?: return null
            sent[id] = Sent(nowMs, g, alert, sender)
            // The carrier already has the message (it came over BLE), and so does an online sender: not 'receivers'.
            received.getOrPut(id) { HashMap() }[carrier] = nowMs
            phones[carrier].src.msgs.add(MailboxItem("m$id", inner, alert))
            return id
        }

        fun runSeconds(n: Int, each: (Int) -> Unit = {}) {
            repeat(n) { s ->
                nowMs += 1000
                each(s)
                relays.forEach { it.newSecond() }
                if (nowMs % 60_000 == 0L) relays.forEach { r -> r.stored.removeAll { it.expiresAt <= nowMs } }
                val due = pending.filter { it.at <= nowMs }
                pending.removeAll(due.toSet())
                for (d in due) if (d.phone in online) phones[d.phone].link.deliver(d.content)
                for (i in online.toList()) runBlocking { phones[i].controller.step() }
            }
        }

        /** Fraction of (message, online receiver) pairs delivered within 30 s, and p95 latency, for alerts or all messages. */
        fun delivery(alertsOnly: Boolean): Pair<Double, Long> {
            var total = 0; var ok = 0; val lats = ArrayList<Long>()
            for ((id, m) in sent) {
                if (alertsOnly && !m.alert) continue
                for (p in onlineMembers(m.group)) {
                    if (p == m.sender) continue
                    total++
                    val at = received[id]?.get(p)
                    if (at != null) { lats.add(at - m.t0); if (at - m.t0 <= 30_000) ok++ }
                }
            }
            lats.sort()
            return (if (total == 0) 1.0 else ok.toDouble() / total) to (if (lats.isEmpty()) -1L else lats[(lats.size * 95 / 100).coerceAtMost(lats.size - 1)])
        }
        fun relayLine() = relays.mapIndexed { i, r -> "r$i peakEv=${r.peakEv}/s peakKB=${r.peakBytes / 1000}/s totalEv=${r.totalEv} rejected=${r.rejected} bans=${r.bans}" }.joinToString("; ")
    }

    private val report = StringBuilder()
    private fun note(s: String) { report.append(s).append('\n') }
    private fun finish(name: String = "scale-sim-report.txt") { File("build").mkdirs(); File("build/$name").writeText(report.toString()) }

    private fun background(w: World, seconds: Int) = w.runSeconds(seconds) { s -> if (s % 6 == 0) w.send(w.rnd.nextInt(w.groups), alert = false) }

    @Test fun `scenarios - baseline, SOS storm, file flood, outage, shared IPs, junk flood, flash crowd`() {
        // A. baseline 1000 users, 30% online: with and without the "skip members who are online" rule
        val a1 = World(1000, 7, 0.3, bridgeAll = true, ipShare = 0).also { it.startRandomOnline(); background(it, 120) }
        val a2 = World(1000, 7, 0.3, bridgeAll = false, ipShare = 0).also { it.startRandomOnline(); background(it, 120) }
        val evAll = a1.relays.sumOf { it.totalEv }; val evSkip = a2.relays.sumOf { it.totalEv }
        note("A baseline 1000 users/300 online/120s: events bridgeAll=$evAll skipOnline=$evSkip (ratio ${"%.2f".format(evSkip.toDouble() / evAll)}); KB bridgeAll=${a1.relays.sumOf { it.totalBytes } / 1000} skipOnline=${a2.relays.sumOf { it.totalBytes } / 1000}")
        note("  skipOnline relays: ${a2.relayLine()}; maxPending/phone=${a2.maxPending}; text delivery ${a2.delivery(false)}")
        // B. SOS storm + file flood together
        val b = World(1000, 7, 0.3, bridgeAll = false, ipShare = 0).also { it.startRandomOnline() }
        for (p in b.online.take(60)) b.phones[p].src.file = FileUplink("f$p", ByteArray(60) { 1 }, 1300) { n -> List(n) { b.rndFrame(420) } }
        b.runSeconds(200) { s -> if (s == 50) for (g in 0 until b.groups step 2) b.send(g, alert = true); if (s % 6 == 0) b.send(b.rnd.nextInt(b.groups), alert = false) }
        note("B SOS storm at t=50 (70 groups) while 60 phones send 400KB files: alert delivery ${b.delivery(true)}, text ${b.delivery(false)}; ${b.relayLine()}; maxPending=${b.maxPending}")
        // C. two of three relays down for 60 s
        val c = World(1000, 7, 0.3, bridgeAll = false, ipShare = 0).also { it.startRandomOnline() }
        c.runSeconds(180) { s -> if (s == 40) { c.relays[0].up = false; c.relays[1].up = false }; if (s == 100) { c.relays[0].up = true; c.relays[1].up = true }; if (s % 6 == 0) c.send(c.rnd.nextInt(c.groups), alert = s % 12 == 0) }
        note("C relay outage (2 of 3 down 60 s): alerts ${c.delivery(true)}, text ${c.delivery(false)}; maxPending=${c.maxPending}")
        // D. carrier-grade NAT: 300 phones share 6 addresses
        val d = World(1000, 7, 0.3, bridgeAll = false, ipShare = 6).also { it.startRandomOnline() }
        background(d, 150)
        note("D shared addresses (6 IPs for 300 phones): text ${d.delivery(false)}, alerts ${d.delivery(true)}; ${d.relayLine()}")
        // E. flash crowd: nobody online, all 300 switch on within 10 s
        val e = World(1000, 7, 0.0, bridgeAll = false, ipShare = 0)
        val toStart = (0 until 1000).filter { e.rnd.nextDouble() < 0.3 }
        e.runSeconds(120) { s -> if (s < 10) toStart.filter { it % 10 == s }.forEach { e.goOnline(it) }; if (s % 6 == 0 && e.online.isNotEmpty()) e.send(e.rnd.nextInt(e.groups), alert = false) }
        note("E flash crowd (300 phones on within 10 s): text ${e.delivery(false)}; ${e.relayLine()}; maxPending=${e.maxPending}")
        // F. junk flood: attacker on one address floods a known group tag with 200 frames/s
        val f = World(1000, 7, 0.3, bridgeAll = false, ipShare = 0).also { it.startRandomOnline() }
        val victim = f.online.first(); val attacker = f.online.first { it != victim && f.phones[it].group != f.phones[victim].group }
        f.runSeconds(120) { s ->
            if (s >= 20) repeat(200) { f.phones[attacker].link.gateway.offer(UplinkWrapper.wrap(f.keys[f.phones[victim].group], MeshFrameCodec.UPLINK_CLASS_LIVE, f.rndFrame(300), f.nowMs / 1000)) }
            if (s % 6 == 0) f.send(f.phones[victim].group, alert = false, fromMember = victim)
        }
        note("F junk flood by one attacker on a victim group's tag (200 frames/s): victim group text ${f.delivery(false)}; attacker queue=${f.phones[attacker].link.gateway.pendingCount()}; ${f.relayLine()}")
        finish()
        assertTrue("queues must stay bounded", maxOf(a2.maxPending, b.maxPending, c.maxPending, d.maxPending, e.maxPending, f.maxPending) < 5000)
    }

    private fun summary(w: World): String {
        val a = w.delivery(true); val t = w.delivery(false)
        val rej = w.relays.sumOf { it.rejected }; val acc = w.relays.sumOf { it.totalEv }
        return "alerts ${"%.0f".format(a.first * 100)}%/p95 ${a.second / 1000}s, text ${"%.0f".format(t.first * 100)}%/p95 ${t.second / 1000}s, peak ${w.relays.maxOf { it.peakEv }} ev/s per relay, accepted $acc rejected $rej bans ${w.relays.sumOf { it.bans }}, maxPending ${w.maxPending}"
    }

    @Test fun `ten thousand users - relay capacity sweep and chaos`() {
        val t0 = System.currentTimeMillis()
        for (cap in listOf(25, 100, 300, 1000)) {
            val w = World(10_000, 7, 0.3, bridgeAll = false, ipShare = 0, capEv = cap, capBytes = cap * 4_000).also { it.startRandomOnline() }
            background(w, 150)
            note("K1 baseline 10000 users/${w.online.size} online, relay capacity $cap ev/s each: ${summary(w)}")
        }
        val s = World(10_000, 7, 0.3, bridgeAll = false, ipShare = 0, capEv = 300, capBytes = 1_200_000).also { it.startRandomOnline() }
        for (p in s.online.take(300)) s.phones[p].src.file = FileUplink("f$p", ByteArray(60) { 1 }, 1300) { n -> List(n) { s.rndFrame(420) } }
        s.runSeconds(200) { sec -> if (sec == 50) for (g in 0 until s.groups step 2) s.send(g, alert = true); if (sec % 3 == 0) s.send(s.rnd.nextInt(s.groups), alert = false) }
        note("K2 alert storm (half of 1429 groups) + 300 file uploads, capacity 300: ${summary(s)}")
        val n = World(10_000, 7, 0.3, bridgeAll = false, ipShare = 20, capEv = 300, capBytes = 1_200_000).also { it.startRandomOnline() }
        background(n, 150)
        note("K3 carrier-grade NAT, 150 phones per address, capacity 300: ${summary(n)}")
        val fl = World(10_000, 7, 0.0, bridgeAll = false, ipShare = 0, capEv = 100, capBytes = 400_000)
        val start = (0 until 10_000).filter { fl.rnd.nextDouble() < 0.3 }
        fl.runSeconds(150) { sec -> if (sec < 10) start.filter { it % 10 == sec }.forEach { fl.goOnline(it) }; if (sec % 3 == 0 && fl.online.isNotEmpty()) fl.send(fl.rnd.nextInt(fl.groups), alert = sec % 6 == 0) }
        note("K4 flash crowd (3000 phones on in 10 s), capacity 100: ${summary(fl)}")
        val o = World(10_000, 7, 0.3, bridgeAll = false, ipShare = 0, capEv = 300, capBytes = 1_200_000).also { it.startRandomOnline() }
        o.runSeconds(200) { sec -> if (sec == 50) { o.relays[0].up = false; o.relays[1].up = false }; if (sec == 110) { o.relays[0].up = true; o.relays[1].up = true }; if (sec % 3 == 0) o.send(o.rnd.nextInt(o.groups), alert = sec % 6 == 0) }
        note("K5 two of three relays down for 60 s, capacity 300: ${summary(o)}")
        note("wall time ${(System.currentTimeMillis() - t0) / 1000}s")
        finish("scale-sim-10k-report.txt")
        assertTrue(true)
    }
}
